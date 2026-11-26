"""Generate the same original stepped socket and clearance plug as STL and 3MF."""
from itertools import product
from pathlib import Path
import struct
from zipfile import ZipFile, ZipInfo, ZIP_DEFLATED

DESTINATION = Path("app/src/androidTest/assets")
# Socket opening 8 × 6 mm, detached plug 7.6 × 5.6 mm, raised 0.9 mm fin.
coords = ([0, .9, 5, 13, 18, 24, 31.6], [0, 5, 5.6, 11, 16], [0, 3, 4, 7.2])
cells = set()
for i, j, k in product(*(range(len(axis) - 1) for axis in coords)):
    x, y, _ = (axis[n] for axis, n in zip(coords, (i, j, k)))
    if (x < 18 and k < 2 and not (5 <= x < 13 and 5 <= y < 11)
            or i == 0 and k == 2 or x == 24 and y < 5.6 and k == 0):
        cells.add((i, j, k))

vertices, triangles, index = [], [], {}
for cell in sorted(cells):
    for axis, direction in product(range(3), (-1, 1)):
        neighbor = list(cell)
        neighbor[axis] += direction
        if tuple(neighbor) in cells:
            continue
        u, v = (axis + 1) % 3, (axis + 2) % 3
        corners = []
        for du, dv in ((0, 0), (1, 0), (1, 1), (0, 1)):
            point = list(cell)
            point[axis] += direction == 1
            point[u] += du
            point[v] += dv
            xyz = tuple(values[n] for values, n in zip(coords, point))
            if xyz not in index:
                index[xyz] = len(vertices)
                vertices.append(xyz)
            corners.append(index[xyz])
        if direction == -1:
            corners.reverse()
        a, b, c, d = corners
        triangles.extend(((a, b, c), (a, c, d)))

edges = {}
for tri in triangles:
    for a, b in zip(tri, (*tri[1:], tri[0])):
        edge = tuple(sorted((a, b)))
        edges.setdefault(edge, []).append(a < b)
assert all(sorted(winding) == [False, True] for winding in edges.values())

with (DESTINATION / "nightprint-clearance.stl").open("wb") as stl:
    stl.write(b"NIGHT PRINT synthetic clearance fixture".ljust(80, b"\0"))
    stl.write(struct.pack("<I", len(triangles)))
    for tri in triangles:
        a, b, c = (vertices[i] for i in tri)
        u, v = ([p - q for p, q in zip(point, a)] for point in (b, c))
        normal = [u[i - 2] * v[i - 1] - u[i - 1] * v[i - 2] for i in range(3)]
        scale = max(map(abs, normal))
        stl.write(struct.pack("<12fH", *(n / scale for n in normal), *a, *b, *c, 0))

vert = "".join(f'<vertex x="{x}" y="{y}" z="{z}"/>' for x, y, z in vertices)
tris = "".join(f'<triangle v1="{a}" v2="{b}" v3="{c}"/>' for a, b, c in triangles)
with ZipFile(DESTINATION / "nightprint-clearance.3mf", "w") as mf:
    entries = {
        "[Content_Types].xml": '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="model" ContentType="application/vnd.ms-package.3dmanufacturing-3dmodel+xml"/></Types>',
        "_rels/.rels": '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Target="/3D/3dmodel.model" Id="r0" Type="http://schemas.microsoft.com/3dmanufacturing/2013/01/3dmodel"/></Relationships>',
        "3D/3dmodel.model": (
            '<model xmlns="http://schemas.microsoft.com/3dmanufacturing/core/2015/02" unit="millimeter">'
            '<resources><object id="1" type="model"><mesh>'
            f'<vertices>{vert}</vertices><triangles>{tris}</triangles>'
            '</mesh></object></resources><build><item objectid="1"/></build></model>'
        ),
    }
    for name, data in entries.items():
        mf.writestr(ZipInfo(name), data, ZIP_DEFLATED)
