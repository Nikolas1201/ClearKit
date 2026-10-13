"""Generate fake test documents for ClearKit on-device testing."""
from PIL import Image, ImageDraw, ImageFont

W, H = 1240, 1754
BG = (255, 255, 255)
INK = (20, 20, 20)
GREY = (110, 110, 110)
ACCENT = (25, 60, 140)


def font(size, bold=False):
    for name in (["arialbd.ttf"] if bold else []) + ["arial.ttf", "DejaVuSans.ttf"]:
        try:
            return ImageFont.truetype(name, size)
        except OSError:
            continue
    return ImageFont.load_default()


def base(title_lines):
    img = Image.new("RGB", (W, H), BG)
    d = ImageDraw.Draw(img)
    y = 90
    for t in title_lines:
        d.text((90, y), t, fill=ACCENT, font=font(44, True))
        y += 62
    d.line([(90, y), (W - 90, y)], fill=ACCENT, width=4)
    return img, d, y + 50


def bill():
    img, d, y = base(["Stadtwerke Musterstadt", "Electricity bill 2025"])
    body = [
        ("Customer no.: 84-2210-991", True),
        ("Invoice no.: RE-2025-04417", True),
        ("Billing period: 01.01.2025 - 30.09.2025", False),
        ("", False),
        ("Consumption: 1.842 kWh  x  0,34 EUR  =  626,28 EUR", False),
        ("Base fee (9 months): 89,10 EUR", False),
        ("Total amount due: 715,38 EUR", True),
        ("", False),
        ("Please pay by 28.11.2026 at the latest.", True),
        ("Late payment adds a reminder fee of 5,00 EUR.", False),
        ("", False),
        ("Questions? Call 030 / 901 820, Mon-Fri 8-18h", False),
        ("or write to service@stadtwerke-muster.de", False),
    ]
    for line, bold in body:
        d.text((90, y), line, fill=INK if bold else GREY, font=font(36, bold))
        y += 58
    d.text((90, H - 140), "Final notice before collection. Do not ignore.", fill=(170, 30, 30), font=font(34, True))
    img.save(r"C:\Users\Nikolas\Documents\Default Project\ClearKit\test-bill.png")


def appointment():
    img, d, y = base(["Praxis Dr. Weber", "Appointment reminder"])
    body = [
        ("Dear patient,", False),
        ("", False),
        ("your check-up is scheduled for", False),
        ("Tuesday, 04.11.2026 at 09:30.", True),
        ("", False),
        ("Please bring: insurance card, medication list.", False),
        ("Take 1x Metformin 500mg in the morning.", True),
        ("", False),
        ("To reschedule call 040 / 44 91 73.", False),
        ("Cancel at least 24h ahead or a 25,00 EUR fee applies.", True),
    ]
    for line, bold in body:
        d.text((90, y), line, fill=INK if bold else GREY, font=font(38, bold))
        y += 62
    img.save(r"C:\Users\Nikolas\Documents\Default Project\ClearKit\test-appointment.png")


bill()
appointment()
print("written")
