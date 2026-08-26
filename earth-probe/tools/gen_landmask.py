#!/usr/bin/env python3
"""Rasterize Natural Earth 110m land polygons into a WxH landmask for the
earth-probe sphere coloring. Writes res/raw/land.txt ('#' = land, '.' = ocean),
row 0 = lat +90 (north pole). Fetch-only dependency: the Natural Earth GeoJSON
(public domain), pinned by URL; run from this directory."""

import json
import urllib.request

W, H = 72, 36
URL = ("https://raw.githubusercontent.com/martynafford/natural-earth-geojson/"
       "master/110m/physical/ne_110m_land.json")


def inside(lon, lat, ring):
    """Ray-cast point-in-polygon on a lon/lat ring (wrapping at 180 handled
    by testing each segment; good enough at 5-degree resolution)."""
    n = len(ring)
    c = False
    for i in range(n):
        x1, y1 = ring[i]
        x2, y2 = ring[(i + 1) % n]
        if (y1 > lat) != (y2 > lat) and \
                lon < x1 + (lat - y1) / (y2 - y1) * (x2 - x1):
            c = not c
    return c


def main():
    with urllib.request.urlopen(URL) as r:
        data = json.load(r)
    grid = [["." for _ in range(W)] for _ in range(H)]
    for feat in data["features"]:
        geom = feat["geometry"]
        rings = geom["coordinates"]
        if geom["type"] == "MultiPolygon":
            rings = [ring for poly in rings for ring in poly]
        else:
            rings = [rings[0]]
        for v in range(H):
            lat = 90 - (v + 0.5) * 180 / H
            for u in range(W):
                lon = (u + 0.5) * 360 / W - 180
                if grid[v][u] == "#":
                    continue
                if any(inside(lon, lat, ring) for ring in rings):
                    grid[v][u] = "#"
    with open("res/raw/land.txt", "w") as f:
        for row in grid:
            f.write("".join(row) + "\n")
    land = sum(row.count("#") for row in grid)
    print(f"wrote res/raw/land.txt {W}x{H}, land cells {land}/{W * H}")


if __name__ == "__main__":
    main()
