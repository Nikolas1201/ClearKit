// ClearKit JNI bridge: on-device vision-language inference via llama.cpp + mtmd.
// Mirrors the tools/mtmd/mtmd-cli.cpp flow (single-turn): tokenize prompt with
// media marker -> eval chunks (image embeddings) -> sample tokens with a
// temp/top-k/top-p chain. Errors return nullptr/false; Kotlin falls back to OCR.
#include <jni.h>

#include <ctime>
#include <string>
#include <thread>
#include <vector>

#include <android/log.h>

#include "llama.h"
#include "ggml-backend.h"
#include "ggml-cpu.h"
#include "mtmd.h"
#include "mtmd-helper.h"

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "ClearKitLlama", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "ClearKitLlama", __VA_ARGS__)

namespace {

constexpr int32_t N_CTX = 4096;
constexpr int32_t N_BATCH = 512;

llama_model * g_model = nullptr;
llama_context * g_ctx = nullptr;
mtmd_context * g_mctx = nullptr;
const llama_vocab * g_vocab = nullptr;

int pick_threads() {
    unsigned hc = std::thread::hardware_concurrency();
    if (hc <= 2) {
        return 2;
    }
    int n = static_cast<int>(hc) - 1;
    return n > 8 ? 8 : n;
}

void free_all() {
    if (g_mctx) {
        mtmd_free(g_mctx);
        g_mctx = nullptr;
    }
    if (g_ctx) {
        llama_free(g_ctx);
        g_ctx = nullptr;
    }
    if (g_model) {
        llama_model_free(g_model);
        g_model = nullptr;
    }
    g_vocab = nullptr;
}

std::string jstr(JNIEnv * env, jstring s) {
    if (!s) {
        return {};
    }
    const char * c = env->GetStringUTFChars(s, nullptr);
    std::string out = c ? c : "";
    if (c) {
        env->ReleaseStringUTFChars(s, c);
    }
    return out;
}

// Decode one assistant token step: add token at n_past, run, advance.
bool decode_one(llama_batch_ext * batch, llama_token tok, llama_pos & n_past) {
    llama_batch_ext_clear(batch);
    const int32_t idx = llama_batch_ext_add_token(batch, 0, tok);
    if (idx < 0) {
        return false;
    }
    if (!llama_batch_ext_set_pos(batch, idx, &n_past)) {
        return false;
    }
    if (!llama_batch_ext_set_output_logits(batch, idx, true)) {
        return false;
    }
    if (llama_process(g_ctx, LLAMA_PROCESS_TYPE_DECODE, batch) != 0) {
        return false;
    }
    n_past += 1;
    return true;
}

} // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_clearkit_app_ai_LlamaBridge_nativeInit(JNIEnv * env, jclass, jstring jModel, jstring jMmproj) {
    const std::string model_path = jstr(env, jModel);
    const std::string mmproj_path = jstr(env, jMmproj);

    free_all();
    llama_backend_init();
    // The CPU backend must be registered explicitly (llama.cpp tools do this
    // in their startup code). Without it, model loading has no backend.
    ggml_backend_register(ggml_backend_cpu_reg());
    llama_log_set([](enum ggml_log_level level, const char * text, void *) {
        if (level == GGML_LOG_LEVEL_ERROR) {
            LOGE("%s", text);
        } else {
            LOGI("%s", text);
        }
    }, nullptr);

    bool ok = false;

    llama_model_params mparams = llama_model_default_params();
    mparams.load_mode = LLAMA_LOAD_MODE_MMAP;
    g_model = llama_model_load_from_file(model_path.c_str(), mparams);
    if (!g_model) {
        LOGE("model load failed: %s", model_path.c_str());
    }

    if (g_model) {
        llama_context_params cparams = llama_context_default_params();
        cparams.n_ctx = N_CTX;
        cparams.n_batch = N_BATCH;
        cparams.n_ubatch = N_BATCH;
        cparams.n_threads = pick_threads();
        cparams.n_threads_batch = pick_threads();
        g_ctx = llama_init_from_model(g_model, cparams);
        if (!g_ctx) {
            LOGE("context init failed");
        }
    }

    if (g_ctx) {
        g_vocab = llama_model_get_vocab(g_model);
        if (!mmproj_path.empty()) {
            mtmd_context_params mp = mtmd_context_params_default();
            mp.use_gpu = false;
            mp.n_threads = pick_threads();
            mp.print_timings = false;
            mp.warmup = false;
            g_mctx = mtmd_init_from_file(mmproj_path.c_str(), g_model, mp);
            ok = (g_mctx != nullptr);
            if (!ok) {
                LOGE("mmproj load failed: %s", mmproj_path.c_str());
            }
        } else {
            ok = true; // text-only session
        }
    }

    if (!ok) {
        free_all();
    } else {
        LOGI("ready (threads=%d)", pick_threads());
    }
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_clearkit_app_ai_LlamaBridge_nativeGenerate(
        JNIEnv * env, jclass, jstring jPrompt, jbyteArray jRgba, jint w, jint h, jint jMaxTokens) {
    if (!g_model || !g_ctx || !g_vocab) {
        return nullptr;
    }
    const std::string prompt = jstr(env, jPrompt);
    if (prompt.empty()) {
        return nullptr;
    }
    const int max_tokens = jMaxTokens > 0 ? (jMaxTokens > 512 ? 512 : jMaxTokens) : 256;

    // Fresh single-turn every call: drop the previous turn's KV cache,
    // otherwise positions restart at 0 against stale cache and eval fails.
    llama_memory_clear(llama_get_memory(g_ctx), true);

    llama_sampler * smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.9f, 1));
    // Repetition penalty BEFORE temp/dist (and after top-k/top-p for speed):
    // without it small models loop forever ("I have seen this... I have seen this...").
    llama_sampler_chain_add(smpl, llama_sampler_init_penalties(
        llama_vocab_n_tokens(g_vocab), /*last_n*/ 64, /*repeat*/ 1.1f, /*freq*/ 0.0f, /*present*/ 0.0f));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(0.7f));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(static_cast<uint32_t>(std::time(nullptr))));

    llama_pos n_past = 0;
    std::string out;
    bool failed = false;

    const bool has_image = (jRgba != nullptr && g_mctx != nullptr && w > 0 && h > 0);

    if (has_image) {
        jbyte * px = env->GetByteArrayElements(jRgba, nullptr);
        jsize len = env->GetArrayLength(jRgba);
        if (!px || len < (jsize)(w * h * 4)) {
            if (px) {
                env->ReleaseByteArrayElements(jRgba, px, JNI_ABORT);
            }
            failed = true;
        } else {
            std::vector<unsigned char> rgb((size_t)w * (size_t)h * 3);
            for (int i = 0; i < w * h; ++i) {
                rgb[3 * i + 0] = static_cast<unsigned char>(px[4 * i + 0]);
                rgb[3 * i + 1] = static_cast<unsigned char>(px[4 * i + 1]);
                rgb[3 * i + 2] = static_cast<unsigned char>(px[4 * i + 2]);
            }
            env->ReleaseByteArrayElements(jRgba, px, JNI_ABORT);

            mtmd_bitmap * bmp = mtmd_bitmap_init((uint32_t)w, (uint32_t)h, rgb.data());
            if (!bmp) {
                failed = true;
            } else {
                const std::string full = std::string(mtmd_default_marker()) + "\n" + prompt;
                const mtmd_input_text txt{full.c_str(), full.size(), /*add_special*/ true, /*parse_special*/ true};
                mtmd_input_chunks * chunks = mtmd_input_chunks_init();
                const mtmd_bitmap * b = bmp;
                const int32_t rc = mtmd_tokenize(g_mctx, chunks, &txt, &b, 1);
                mtmd_bitmap_free(bmp);
                if (rc != 0) {
                    LOGE("mtmd_tokenize failed: %d", rc);
                    mtmd_input_chunks_free(chunks);
                    failed = true;
                } else {
                    llama_pos new_past = 0;
                    const int32_t erc = mtmd_helper_eval_chunks(
                        g_mctx, g_ctx, chunks, /*n_past*/ 0, /*seq_id*/ 0, N_BATCH,
                        /*logits_last*/ true, &new_past);
                    mtmd_input_chunks_free(chunks);
                    if (erc != 0) {
                        LOGE("eval_chunks failed: %d", erc);
                        failed = true;
                    } else {
                        n_past = new_past;
                    }
                }
            }
        }
    } else {
        // text-only prompt
        std::vector<llama_token> toks(prompt.size() + 16);
        int32_t n = llama_tokenize(g_vocab, prompt.c_str(), (int32_t)prompt.size(),
                                   toks.data(), (int32_t)toks.size(), /*add_special*/ true, /*parse_special*/ false);
        if (n < 0) {
            toks.resize((size_t)(-n));
            n = llama_tokenize(g_vocab, prompt.c_str(), (int32_t)prompt.size(),
                               toks.data(), (int32_t)toks.size(), true, false);
        }
        if (n <= 0) {
            failed = true;
        } else {
            toks.resize((size_t)n);
            llama_batch_ext * b = llama_batch_ext_init(g_ctx);
            for (int32_t i = 0; i < n && !failed; ++i) {
                const bool last = (i == n - 1);
                llama_batch_ext_clear(b);
                const int32_t idx = llama_batch_ext_add_token(b, 0, toks[(size_t)i]);
                if (idx < 0 || !llama_batch_ext_set_pos(b, idx, &n_past) ||
                    !llama_batch_ext_set_output_logits(b, idx, last) ||
                    llama_process(g_ctx, LLAMA_PROCESS_TYPE_DECODE, b) != 0) {
                    failed = true;
                    break;
                }
                n_past += 1;
            }
            llama_batch_ext_free(b);
        }
    }

    if (!failed) {
        llama_batch_ext * batch = llama_batch_ext_init(g_ctx);
        char piece[64];
        for (int i = 0; i < max_tokens; ++i) {
            const llama_token tok = llama_sampler_sample(smpl, g_ctx, -1);
            llama_sampler_accept(smpl, tok);
            if (llama_vocab_is_eog(g_vocab, tok)) {
                break;
            }
            const int32_t pl = llama_token_to_piece(g_vocab, tok, piece, sizeof(piece), 0, false);
            if (pl > 0) {
                out.append(piece, (size_t)pl);
            }
            if (!decode_one(batch, tok, n_past)) {
                break;
            }
            if (n_past >= N_CTX - 8) {
                break;
            }
        }
        llama_batch_ext_free(batch);
    }

    llama_sampler_free(smpl);

    if (failed || out.empty()) {
        return nullptr;
    }
    return env->NewStringUTF(out.c_str());
}

JNIEXPORT void JNICALL
Java_com_clearkit_app_ai_LlamaBridge_nativeFree(JNIEnv *, jclass) {
    free_all();
}

} // extern "C"
