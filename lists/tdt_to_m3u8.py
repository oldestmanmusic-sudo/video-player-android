"""Convierte TELEVISION.md y RADIO.md de TDTChannels en listas M3U8 con grupos, logos y EPG.

Uso:  python3 tdt_to_m3u8.py TELEVISION.md RADIO.md  salida_dir
Genera: tv.m3u8, radio.m3u8 y tvradio.m3u8 (las emisoras de radio llevan radio="true").
"""
import os
import re
import sys

TV_LINK = re.compile(r"\[(m3u8[^\]]*)\]\((https?://[^)\s]+)\)")
ANY_LINK = re.compile(r"\[([^\]]*)\]\((https?://[^)\s]+)\)")
LOGO = re.compile(r"\((https?://[^)\s]+)\)")
SKIP_KINDS = ("youtube", "twitch", "web", "emb", "dailymotion", "vimeo")


def usable(url):
    # Enlaces con marcadores de plantilla ([LMT], [IP]...) no sirven sin sustituirlos.
    return "[" not in url and "]" not in url


def radio_links(cell):
    """Un solo enlace por emisora: aac primero, luego mp3 y el resto."""
    links = [(k.strip().lower(), u) for k, u in ANY_LINK.findall(cell) if usable(u)]
    links = [(k, u) for k, u in links if not k.startswith(SKIP_KINDS)]
    for wanted in ("aac", "mp3"):
        for kind, url in links:
            if kind.startswith(wanted):
                return [url]
    return [links[0][1]] if links else []


def convert(path, radio):
    group = ""
    entries = []
    skipped = 0
    for line in open(path, encoding="utf-8"):
        line = line.rstrip("\n")
        heading = re.match(r"^#{2,4}\s+(.*)", line)
        if heading:
            group = heading.group(1).strip()
            continue
        if not line.startswith("|"):
            continue
        cells = [c.strip() for c in line.strip().strip("|").split("|")]
        if len(cells) < 5 or cells[0] in ("Canal", "Emisoras", "-") or set(cells[0]) <= {"-", " "}:
            continue
        name, link_cell = cells[0], cells[1]
        logo = LOGO.search(cells[3])
        epg = cells[4] if cells[4] != "-" else ""

        if radio:
            urls = radio_links(link_cell)
        else:
            found = TV_LINK.findall(link_cell)
            urls = [u for _, u in found if usable(u)]
            skipped += len(found) - len(urls)
        for n, url in enumerate(urls, 1):
            title = name if len(urls) == 1 else f"{name} #{n}"
            attrs = f'tvg-id="{epg}" tvg-name="{name}" group-title="{group}"'
            if radio:
                attrs += ' radio="true"'
            if logo:
                attrs += f' tvg-logo="{logo.group(1)}"'
            entries.append(f"#EXTINF:-1 {attrs},{title}\n{url}")
    return entries, skipped


def write(path, entries):
    with open(path, "w", encoding="utf-8") as f:
        f.write("#EXTM3U\n" + "\n".join(entries) + "\n")


tv_md, radio_md, out_dir = sys.argv[1], sys.argv[2], sys.argv[3]
os.makedirs(out_dir, exist_ok=True)
tv, skipped = convert(tv_md, radio=False)
radio, _ = convert(radio_md, radio=True)
write(os.path.join(out_dir, "tv.m3u8"), tv)
write(os.path.join(out_dir, "radio.m3u8"), radio)
write(os.path.join(out_dir, "tvradio.m3u8"), tv + radio)
print(f"TV: {len(tv)} emisiones ({skipped} omitidas por plantilla) | Radio: {len(radio)} emisoras")
