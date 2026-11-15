import sys, os, io, urllib.request
from PIL import Image, ImageDraw
URL = "https://capivariashopbr.store/__l5e/assets-v1/9488f642-2358-468f-93e1-fe1ab185d52e/logo-capivaria.png"
res = sys.argv[1]
here = os.path.dirname(os.path.abspath(__file__))

def load():
    local = os.path.join(here, "logo-capivaria.png")
    if os.path.exists(local):
        print("Usando tools/logo-capivaria.png do repositorio")
        return Image.open(local).convert("RGBA")
    try:
        req = urllib.request.Request(URL, headers={"User-Agent": "Mozilla/5.0"})
        im = Image.open(io.BytesIO(urllib.request.urlopen(req, timeout=30).read())).convert("RGBA")
        print("Logo da Capivaria baixada do site")
        return im
    except Exception as e:
        print("AVISO: nao foi possivel obter a logo, usando placeholder:", e)
        return None

logo = load()
if logo is not None:
    bb = logo.getbbox()
    if bb: logo = logo.crop(bb)

# logo usada dentro do app (abertura, login, cabecalho)
os.makedirs(f"{res}/drawable-nodpi", exist_ok=True)
if logo is not None:
    big = logo.copy(); big.thumbnail((512, 512), Image.LANCZOS)
else:
    big = Image.new("RGBA", (256, 256), (0, 0, 0, 0)); ImageDraw.Draw(big).ellipse((16, 16, 240, 240), fill=(34, 197, 94, 255))
big.save(f"{res}/drawable-nodpi/logo_capivaria.png")

# icone do aplicativo (emblema)
for d, px in {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}.items():
    os.makedirs(f"{res}/mipmap-{d}", exist_ok=True)
    c = Image.new("RGBA", (px, px), (13, 19, 17, 255))
    src = logo if logo is not None else big
    r = min(px * 0.78 / src.width, px * 0.78 / src.height)
    l = src.resize((max(1, int(src.width * r)), max(1, int(src.height * r))), Image.LANCZOS)
    c.alpha_composite(l, ((px - l.width) // 2, (px - l.height) // 2))
    c.save(f"{res}/mipmap-{d}/ic_launcher.png"); c.save(f"{res}/mipmap-{d}/ic_launcher_round.png")
