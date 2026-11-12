"""CASI Shop - product pictures "CASI Ontologie" and "CASI Helping Hands Service".

Draws one SVG per product per Commerce image format (1200Wx1200H superZoom, 515Wx515H zoom,
300Wx300H product, 96Wx96H thumbnail, 65Wx65H cartIcon, 30Wx30H styleSwatch). Each file has the
format's pixel size, and the artwork is simplified as it gets smaller:
  1200 / 515  full picture: graph with class names, title and caption
  300         no class names or caption, title kept
  96 / 65     icon: the glyph alone, cropped tight, heavier strokes
  30          swatch: the glyph reduced to a few shapes

The files are written to images/ next to this script. With --upload they are pushed to the
tenant through HAC Groovy (LandscapeCommander's HAC client): one MediaContainer per product in
casiProductCatalog Staged and Online, a Media per format, set as picture (300), thumbnail (96)
and gallery image. Then the casiIndex Solr index is rebuilt so listing pages show the pictures.

usage:
  python 10_product_images.py                       # write images/*.svg only
  python 10_product_images.py --upload              # write, upload to supernova, re-index
  python 10_product_images.py --upload --system X   # another LandscapeCommander alias
"""
from __future__ import annotations

import argparse
import base64
import json
import math
import os
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
OUT = HERE / "images"
LC_REPO = Path(os.environ.get("LANDSCAPE_COMMANDER", HERE.parents[2] / "LandscapeComander"))

CATALOG = "casiProductCatalog"
INDEX = "casiIndex"
FORMATS = {  # format -> detail tier
    "1200Wx1200H": "L", "515Wx515H": "L", "300Wx300H": "M",
    "96Wx96H": "S", "65Wx65H": "S", "30Wx30H": "XS",
}

# CASI Shop palette (js-storefront/casishop/src/styles/casi-theme.scss)
PETROL = "#0f5c6e"
PETROL_DEEP = "#0a4452"
INK = "#10262e"
AMBER = "#e8a33d"
MIST = "#e3edf0"
PAPER = "#f4f8f9"
LINE = "#c3d3d8"
WHITE = "#ffffff"
FONT = "'Segoe UI', 'Helvetica Neue', Arial, sans-serif"

# A crop of the 1200 canvas per tier: icons zoom into the glyph so it fills the frame.
VIEWBOX = {"L": "0 0 1200 1200", "M": "0 0 1200 1200", "S": "120 110 960 960", "XS": "170 160 860 860"}


def svg(fmt: str, title: str, body: str, defs: str) -> str:
    w, h = fmt.split("Wx")
    h = h.rstrip("H")
    tier = FORMATS[fmt]
    return (
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{w}" height="{h}" viewBox="{VIEWBOX[tier]}" '
        f'role="img" aria-label="{title}"><title>{title}</title><defs>{defs}</defs>{body}</svg>\n'
    )


def background(tier: str, top: str, bottom: str, dots: bool) -> tuple[str, str]:
    defs = (f'<linearGradient id="bg" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="{top}"/>'
            f'<stop offset="1" stop-color="{bottom}"/></linearGradient>')
    body = '<rect x="0" y="0" width="1200" height="1200" fill="url(#bg)"/>'
    if dots and tier in ("L", "M"):
        # Squared paper behind the graph: the modelling sheet the ontology is drawn on.
        defs += (f'<pattern id="dots" width="60" height="60" patternUnits="userSpaceOnUse">'
                 f'<circle cx="30" cy="30" r="3" fill="{PETROL}" opacity="0.16"/></pattern>')
        body += '<rect x="0" y="0" width="1200" height="1200" fill="url(#dots)"/>'
    if tier in ("L", "M"):
        body += f'<circle cx="600" cy="540" r="440" fill="{WHITE}" opacity="0.55"/>'
    return defs, body


def caption(tier: str, title: str, sub: str) -> str:
    if tier == "L":
        return (f'<rect x="0" y="1010" width="1200" height="190" fill="{INK}"/>'
                f'<rect x="0" y="1010" width="1200" height="8" fill="{AMBER}"/>'
                f'<text x="600" y="1098" text-anchor="middle" font-family="{FONT}" font-size="66" '
                f'font-weight="700" fill="{WHITE}">{title}</text>'
                f'<text x="600" y="1152" text-anchor="middle" font-family="{FONT}" font-size="30" '
                f'fill="{MIST}">{sub}</text>')
    if tier == "M":
        return (f'<rect x="0" y="1010" width="1200" height="190" fill="{INK}"/>'
                f'<rect x="0" y="1010" width="1200" height="10" fill="{AMBER}"/>'
                f'<text x="600" y="1132" text-anchor="middle" font-family="{FONT}" font-size="84" '
                f'font-weight="700" fill="{WHITE}">{title}</text>')
    return ""


# --- CASI Ontologie -----------------------------------------------------------------------
# An ontology graph: the class "Order" in the middle, linked to the classes it relates to.
HUB = (600, 520)
CLASSES = [  # name, x, y
    ("Customer", 600, 215), ("Product", 905, 365), ("Delivery", 905, 675),
    ("Invoice", 600, 825), ("Return", 295, 675), ("Payment", 295, 365),
]
CROSS = [("Customer", "Payment"), ("Product", "Return"), ("Delivery", "Invoice"), ("Customer", "Product")]


def _pos(name: str) -> tuple[int, int]:
    return next((x, y) for n, x, y in CLASSES if n == name)


def ontology_glyph(tier: str) -> str:
    out = []
    edge_w = {"L": 9, "M": 12, "S": 22, "XS": 40}[tier]
    classes = CLASSES if tier != "XS" else [CLASSES[i] for i in (0, 1, 3, 4)]
    # cross relations (dashed) between classes
    if tier in ("L", "M"):
        for a, b in CROSS:
            (x1, y1), (x2, y2) = _pos(a), _pos(b)
            mx, my = (x1 + x2) / 2, (y1 + y2) / 2
            # bow the curve away from the hub
            dx, dy = mx - HUB[0], my - HUB[1]
            d = math.hypot(dx, dy) or 1
            cx, cy = mx + dx / d * 70, my + dy / d * 70
            out.append(f'<path d="M{x1} {y1} Q{cx:.0f} {cy:.0f} {x2} {y2}" fill="none" stroke="{PETROL}" '
                       f'stroke-width="6" stroke-dasharray="16 14" opacity="0.55"/>')
    # data-property leaves (L only)
    if tier == "L":
        for _, x, y in CLASSES:
            dx, dy = x - HUB[0], y - HUB[1]
            ang = math.atan2(dy, dx)
            for off in (-0.62, 0.62):
                lx = x + math.cos(ang + off) * 175
                ly = y + math.sin(ang + off) * 130
                out.append(f'<line x1="{x}" y1="{y}" x2="{lx:.0f}" y2="{ly:.0f}" stroke="{PETROL}" stroke-width="5"/>'
                           f'<circle cx="{lx:.0f}" cy="{ly:.0f}" r="15" fill="{WHITE}" stroke="{PETROL}" stroke-width="6"/>')
    # object relations hub -> class
    for _, x, y in classes:
        out.append(f'<line x1="{HUB[0]}" y1="{HUB[1]}" x2="{x}" y2="{y}" stroke="{INK}" '
                   f'stroke-width="{edge_w}" stroke-linecap="round"/>')
    # class nodes
    if tier == "L":
        for name, x, y in CLASSES:
            out.append(f'<rect x="{x - 120}" y="{y - 40}" width="240" height="80" rx="40" fill="{PETROL}" '
                       f'stroke="{INK}" stroke-width="8"/>'
                       f'<text x="{x}" y="{y + 14}" text-anchor="middle" font-family="{FONT}" font-size="40" '
                       f'font-weight="600" fill="{WHITE}">{name}</text>')
        out.append(f'<rect x="{HUB[0] - 140}" y="{HUB[1] - 56}" width="280" height="112" rx="56" fill="{AMBER}" '
                   f'stroke="{INK}" stroke-width="10"/>'
                   f'<text x="{HUB[0]}" y="{HUB[1] + 19}" text-anchor="middle" font-family="{FONT}" font-size="54" '
                   f'font-weight="800" fill="{INK}">Order</text>')
    else:
        r = {"M": 58, "S": 86, "XS": 120}[tier]
        hub_r = {"M": 92, "S": 120, "XS": 150}[tier]
        sw = {"M": 10, "S": 16, "XS": 26}[tier]
        for _, x, y in classes:
            out.append(f'<circle cx="{x}" cy="{y}" r="{r}" fill="{PETROL}" stroke="{INK}" stroke-width="{sw}"/>')
            if tier != "XS":
                out.append(f'<circle cx="{x}" cy="{y}" r="{r * 0.38:.0f}" fill="{WHITE}"/>')
        out.append(f'<circle cx="{HUB[0]}" cy="{HUB[1]}" r="{hub_r}" fill="{AMBER}" stroke="{INK}" stroke-width="{sw}"/>')
        if tier != "XS":
            out.append(f'<circle cx="{HUB[0]}" cy="{HUB[1]}" r="{hub_r * 0.36:.0f}" fill="{INK}"/>')
    return "".join(out)


def ontology(fmt: str) -> str:
    tier = FORMATS[fmt]
    defs, body = background(tier, MIST, PAPER, dots=True)
    if tier in ("S", "XS"):
        # icons: centre the glyph in the crop
        body += f'<g transform="translate(0 70)">{ontology_glyph(tier)}</g>'
    else:
        body += ontology_glyph(tier)
    body += caption(tier, "CASI Ontologie", "Basic Ontology · customers, products, orders, returns")
    return svg(fmt, "CASI Ontologie", body, defs)


# --- CASI Helping Hands Service -----------------------------------------------------------
# Two open hands holding up the ontology: the service plan that looks after it.
LEFT_HAND = (
    "M232 742 C292 732 352 736 400 752 "      # top of the wrist to the thumb root
    "C412 712 436 676 470 664 "                # thumb rising
    "C504 652 528 676 516 704 "                # thumb tip
    "C506 730 488 756 478 782 "                # thumb back into the palm
    "C516 808 548 806 572 778 "                # inside of the palm, rising to the fingers
    "C586 758 604 768 597 796 "                # fingertip curl at the centre
    "C584 852 514 906 420 912 "                # back of the fingers
    "C356 916 292 908 232 896 Z"               # heel of the hand back to the wrist
)
FINGER_LINES = [  # finger separations on the back of the curled fingers
    "M590 818 C576 840 554 858 524 870",
    "M580 846 C562 868 536 886 498 896",
]


def hands(tier: str) -> str:
    sw = {"L": 12, "M": 14, "S": 22, "XS": 34}[tier]
    hand = (f'<path d="{LEFT_HAND}" fill="{WHITE}" stroke="{INK}" stroke-width="{sw}" '
            f'stroke-linejoin="round" stroke-linecap="round"/>')
    if tier in ("L", "M"):
        hand += "".join(f'<path d="{d}" fill="none" stroke="{INK}" stroke-width="{max(sw - 4, 6)}" '
                        f'stroke-linecap="round"/>' for d in FINGER_LINES)
    cuff = (f'<g transform="rotate(-14 200 820)"><rect x="118" y="706" width="132" height="236" rx="26" '
            f'fill="{PETROL}" stroke="{INK}" stroke-width="{sw}"/>')
    if tier != "XS":
        cuff += f'<rect x="216" y="716" width="22" height="216" fill="{AMBER}"/>'
    cuff += '</g>'
    left = cuff + hand
    right = f'<g transform="translate(1200 0) scale(-1 1)">{left}</g>'
    return left + right


def held_graph(tier: str) -> str:
    cx, cy = 600, 520
    nodes = [(600, 330), (430, 470), (770, 470)]
    sw = {"L": 10, "M": 12, "S": 20, "XS": 30}[tier]
    out = []
    if tier in ("L", "M"):
        # a soft glow: the plan keeps the ontology alive
        for i, r in enumerate((230, 175)):
            out.append(f'<circle cx="{cx}" cy="{cy}" r="{r}" fill="{AMBER}" opacity="{0.12 + i * 0.1:.2f}"/>')
        for k in range(8):
            a = math.radians(k * 45 + 22.5)
            x1, y1 = cx + math.cos(a) * 250, cy + math.sin(a) * 250
            x2, y2 = cx + math.cos(a) * 292, cy + math.sin(a) * 292
            if y2 > 640:  # keep the rays out of the hands
                continue
            out.append(f'<line x1="{x1:.0f}" y1="{y1:.0f}" x2="{x2:.0f}" y2="{y2:.0f}" stroke="{AMBER}" '
                       f'stroke-width="12" stroke-linecap="round"/>')
    if tier != "XS":
        for x, y in nodes:
            out.append(f'<line x1="{cx}" y1="{cy}" x2="{x}" y2="{y}" stroke="{INK}" stroke-width="{sw}" '
                       f'stroke-linecap="round"/>')
        out.append(f'<line x1="{nodes[1][0]}" y1="{nodes[1][1]}" x2="{nodes[0][0]}" y2="{nodes[0][1]}" '
                   f'stroke="{PETROL}" stroke-width="{max(sw - 4, 6)}" stroke-dasharray="14 12" opacity="0.6"/>')
        r = {"L": 46, "M": 52, "S": 64}[tier]
        for x, y in nodes:
            out.append(f'<circle cx="{x}" cy="{y}" r="{r}" fill="{PETROL}" stroke="{INK}" stroke-width="{sw}"/>')
    hub_r = {"L": 92, "M": 98, "S": 118, "XS": 170}[tier]
    out.append(f'<circle cx="{cx}" cy="{cy}" r="{hub_r}" fill="{AMBER}" stroke="{INK}" stroke-width="{sw}"/>')
    if tier != "XS":
        out.append(f'<circle cx="{cx}" cy="{cy}" r="{hub_r * 0.36:.0f}" fill="{INK}"/>')
    return "".join(out)


def badge(tier: str) -> str:
    if tier not in ("L", "M"):
        return ""
    return (f'<circle cx="1010" cy="190" r="118" fill="{AMBER}" stroke="{INK}" stroke-width="10"/>'
            f'<text x="1010" y="186" text-anchor="middle" font-family="{FONT}" font-size="76" font-weight="800" '
            f'fill="{INK}">1</text>'
            f'<text x="1010" y="236" text-anchor="middle" font-family="{FONT}" font-size="36" font-weight="700" '
            f'letter-spacing="4" fill="{INK}">YEAR</text>')


def helping_hands(fmt: str) -> str:
    tier = FORMATS[fmt]
    defs, body = background(tier, "#d5e6ea", PAPER, dots=False)
    # the held graph sits low and the hands come up to meet it
    glyph = f'<g transform="translate(0 30)">{held_graph(tier)}</g><g transform="translate(0 -30)">{hands(tier)}</g>'
    if tier in ("S", "XS"):
        body += f'<g transform="translate(0 -40)">{glyph}</g>'
    else:
        body += glyph + badge(tier)
    body += caption(tier, "CASI Helping Hands Service", "1-year service plan · ontology updates and expert support")
    return svg(fmt, "CASI Helping Hands Service", body, defs)


PRODUCTS = {  # product code -> (file stem, picture name, drawing)
    "CASI-ONTOLOGY": ("casi-ontologie", "CASI Ontologie", ontology),
    "CASI-ONTOLOGY-PLAN": ("casi-helping-hands-service", "CASI Helping Hands Service", helping_hands),
}


def write_all() -> dict[str, dict[str, str]]:
    OUT.mkdir(exist_ok=True)
    files: dict[str, dict[str, str]] = {}
    for code, (stem, _, draw) in PRODUCTS.items():
        files[code] = {}
        for fmt in FORMATS:
            text = draw(fmt)
            (OUT / f"{stem}-{fmt}.svg").write_text(text, encoding="utf-8")
            files[code][fmt] = text
    return files


# HAC Groovy. CCv2's XSS filter strips anything between a less-than and a greater-than sign
# and "src=" text from HAC requests, so the SVGs travel base64-encoded and the script avoids both.
UPLOAD = r"""
import de.hybris.platform.core.model.media.MediaContainerModel
import de.hybris.platform.core.model.media.MediaModel
import de.hybris.platform.servicelayer.search.FlexibleSearchQuery

def PICS = new groovy.json.JsonSlurper().parseText(new String(Base64.getDecoder().decode('__PICS__'), 'UTF-8'))
def folder = mediaService.getFolder('images')
def findOne = { String type, String attr, String value, cv ->
  def q = new FlexibleSearchQuery("SELECT {pk} FROM {" + type + "} WHERE {" + attr + "}=?v AND {catalogVersion}=?cv")
  q.addQueryParameter('v', value)
  q.addQueryParameter('cv', cv)
  def r = flexibleSearchService.search(q).result
  r ? r[0] : null
}
['Staged', 'Online'].each { ver ->
  def cv = catalogVersionService.getCatalogVersion('__CATALOG__', ver)
  PICS.each { code, pic ->
    def p = productService.getProductForCode(cv, code)
    def qual = 'casi-img-' + code
    MediaContainerModel cont = findOne('MediaContainer', 'qualifier', qual, cv)
    if (cont == null) {
      cont = modelService.create(MediaContainerModel)
      cont.qualifier = qual
      cont.catalogVersion = cv
      modelService.save(cont)
    }
    def medias = [:]
    pic.files.each { fmt, b64 ->
      def mcode = qual + '-' + fmt
      MediaModel m = findOne('Media', 'code', mcode, cv) ?: modelService.create(MediaModel)
      m.code = mcode
      m.catalogVersion = cv
      m.mediaFormat = mediaService.getFormat(fmt)
      m.mediaContainer = cont
      m.folder = folder
      m.altText = pic.name
      modelService.save(m)
      mediaService.setStreamForMedia(m, new ByteArrayInputStream(b64.decodeBase64()), pic.stem + '-' + fmt + '.svg', 'image/svg+xml')
      medias[fmt] = m
    }
    p.picture = medias['300Wx300H']
    p.thumbnail = medias['96Wx96H']
    p.galleryImages = [cont]
    modelService.save(p)
    println ver + ' ' + code + ': ' + medias.size() + ' formats, picture ' + medias['300Wx300H'].URL
  }
}
'done'
"""

REINDEX = """
def ctx = de.hybris.platform.core.Registry.getCoreApplicationContext()
ctx.getBean('defaultFacetSearchConfigCacheService').invalidate('__INDEX__')
def cfg = ctx.getBean('defaultFacetSearchConfigService').getConfiguration('__INDEX__')
ctx.getBean('defaultIndexerService').performFullIndex(cfg)
'full index of __INDEX__ done'
"""


def upload(files: dict[str, dict[str, str]], alias: str) -> None:
    sys.path.insert(0, str(LC_REPO))
    from core.config import load_env  # noqa: E402
    from systems.cc_v2 import CommerceCloudV2  # noqa: E402

    load_env(str(LC_REPO / ".env"))
    system = CommerceCloudV2(alias=alias)
    pics = {
        code: {"stem": PRODUCTS[code][0], "name": PRODUCTS[code][1],
               "files": {fmt: base64.b64encode(t.encode("utf-8")).decode("ascii") for fmt, t in by_fmt.items()}}
        for code, by_fmt in files.items()
    }
    # one product per call: a Groovy string literal holds at most 65535 characters
    for code, pic in pics.items():
        payload = base64.b64encode(json.dumps({code: pic}).encode("utf-8")).decode("ascii")
        res = system.run_groovy(UPLOAD.replace("__PICS__", payload).replace("__CATALOG__", CATALOG))
        print(res.get("outputText", "").strip())
    res = system.run_groovy(REINDEX.replace("__INDEX__", INDEX))
    print(res.get("executionResult"))


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--upload", action="store_true", help="upload to the tenant and re-index")
    ap.add_argument("--system", default="supernova", help="LandscapeCommander alias (default supernova)")
    args = ap.parse_args()
    files = write_all()
    print(f"wrote {sum(len(v) for v in files.values())} SVGs to {OUT}")
    if args.upload:
        upload(files, args.system)


if __name__ == "__main__":
    main()
