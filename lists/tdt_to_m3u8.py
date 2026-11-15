"""Convierte TELEVISION.md de TDTChannels en una lista M3U8 con grupos, logos y EPG."""
import re
import sys

src, dst = sys.argv[1], sys.argv[2]
LINK = re.compile(r"\[(m3u8[^\]]*)\]\((https?://[^)\s]+)\)")

group = ""
out = ["#EXTM3U"]
count = skipped = 0

for line in open(src, encoding="utf-8"):
    line = line.rstrip("\n")
    heading = re.match(r"^#{2,4}\s+(.*)", line)
    if heading:
        group = heading.group(1).strip()
        continue
    if not line.startswith("|"):
        continue
    cells = [c.strip() for c in line.strip().strip("|").split("|")]
    if len(cells) < 5 or cells[0] in ("Canal", "-") or set(cells[0]) <= {"-", " "}:
        continue
    name, m3u8_cell = cells[0], cells[1]
    logo = re.search(r"\((https?://[^)\s]+)\)", cells[3])
    epg = cells[4] if cells[4] != "-" else ""

    links = LINK.findall(m3u8_cell)
    # Enlaces con marcadores de plantilla ([LMT], [IP]...) no sirven sin sustituirlos.
    usable = [u for _, u in links if "[" not in u and "]" not in u]
    skipped += len(links) - len(usable)
    for n, url in enumerate(usable, 1):
        title = name if len(usable) == 1 else f"{name} #{n}"
        attrs = f'tvg-id="{epg}" tvg-name="{name}" group-title="{group}"'
        if logo:
            attrs += f' tvg-logo="{logo.group(1)}"'
        out.append(f"#EXTINF:-1 {attrs},{title}")
        out.append(url)
        count += 1

open(dst, "w", encoding="utf-8").write("\n".join(out) + "\n")
print(f"{count} emisiones escritas, {skipped} omitidas (enlaces con plantilla)")
