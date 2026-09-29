from pathlib import Path
import math
import struct
import zlib

OUT = Path("visual-remaster/assets/models/battle/dart/combat")
OUT.mkdir(parents=True, exist_ok=True)

def normalize(v):
    x, y, z = v
    l = math.sqrt(x*x + y*y + z*z)
    return (0.0, 1.0, 0.0) if l == 0.0 else (x/l, y/l, z/l)

def cross(a, b):
    return (
        a[1]*b[2] - a[2]*b[1],
        a[2]*b[0] - a[0]*b[2],
        a[0]*b[1] - a[1]*b[0],
    )

def sub(a, b):
    return (a[0]-b[0], a[1]-b[1], a[2]-b[2])

def face_normal(a, b, c):
    return normalize(cross(sub(b, a), sub(c, a)))

def write_obj(path, vertices, triangles, uvs, name):
    lines = ["# Visual Remaster generated milestone geometry", f"o {name}"]
    for x, y, z in vertices:
        lines.append(f"v {x:.6f} {y:.6f} {z:.6f}")
    for u, v in uvs:
        lines.append(f"vt {u:.8f} {v:.8f}")

    normals = []
    faces = []
    for tri in triangles:
        n = face_normal(vertices[tri[0]], vertices[tri[1]], vertices[tri[2]])
        normals.append(n)
        faces.append((tri, len(normals)))

    for x, y, z in normals:
        lines.append(f"vn {x:.8f} {y:.8f} {z:.8f}")

    for tri, ni in faces:
        lines.append("f " + " ".join(f"{idx+1}/{idx+1}/{ni}" for idx in tri))

    path.write_text("\n".join(lines) + "\n", encoding="utf-8")

def add_uv_sphere(vertices, triangles, uvs, center, radii, rings, segments, uv_region):
    base = len(vertices)
    cx, cy, cz = center
    rx, ry, rz = radii
    u0, v0, u1, v1 = uv_region

    for r in range(rings + 1):
        phi = math.pi * r / rings
        y = cy + math.cos(phi) * ry
        sr = math.sin(phi)
        for s in range(segments + 1):
            theta = 2.0 * math.pi * s / segments
            vertices.append((
                cx + math.cos(theta) * sr * rx,
                y,
                cz + math.sin(theta) * sr * rz,
            ))
            uvs.append((
                u0 + (u1-u0) * s / segments,
                v0 + (v1-v0) * (1.0-r/rings),
            ))

    for r in range(rings):
        for s in range(segments):
            a = base + r * (segments + 1) + s
            b = a + 1
            c = base + (r + 1) * (segments + 1) + s
            d = c + 1
            if r > 0:
                triangles.append((a, c, b))
            if r < rings - 1:
                triangles.append((b, c, d))

def add_cone(vertices, triangles, uvs, base_center, direction, radius, length, segments, uv):
    dx, dy, dz = normalize(direction)
    helper = (0.0, 1.0, 0.0) if abs(dy) < 0.9 else (1.0, 0.0, 0.0)
    ux, uy, uz = normalize(cross((dx,dy,dz), helper))
    vx, vy, vz = normalize(cross((dx,dy,dz), (ux,uy,uz)))
    bx, by, bz = base_center
    base = len(vertices)

    for i in range(segments):
        a = 2.0 * math.pi * i / segments
        ca, sa = math.cos(a), math.sin(a)
        vertices.append((
            bx + (ca*ux + sa*vx) * radius,
            by + (ca*uy + sa*vy) * radius,
            bz + (ca*uz + sa*vz) * radius,
        ))
        uvs.append(uv)

    vertices.append((bx + dx*length, by + dy*length, bz + dz*length))
    uvs.append(uv)
    tip = base + segments

    for i in range(segments):
        triangles.append((base+i, base+(i+1)%segments, tip))

def add_box(vertices, triangles, uvs, center, half, uv, taper_top=1.0):
    cx, cy, cz = center
    hx, hy, hz = half
    pts = [
        (-hx,-hy,-hz),(hx,-hy,-hz),(hx,-hy,hz),(-hx,-hy,hz),
        (-hx*taper_top,hy,-hz*taper_top),(hx*taper_top,hy,-hz*taper_top),
        (hx*taper_top,hy,hz*taper_top),(-hx*taper_top,hy,hz*taper_top),
    ]
    base = len(vertices)
    for x,y,z in pts:
        vertices.append((cx+x,cy+y,cz+z))
        uvs.append(uv)

    for f in [
        (0,1,2),(0,2,3),(4,6,5),(4,7,6),
        (0,4,5),(0,5,1),(1,5,6),(1,6,2),
        (2,6,7),(2,7,3),(3,7,4),(3,4,0),
    ]:
        triangles.append(tuple(base+i for i in f))

def write_solid_png(path, width, height, rows):
    raw = bytearray()
    for y in range(height):
        raw.append(0)
        row = rows(y)
        for x in range(width):
            r,g,b = row(x)
            raw.extend((r,g,b,255))

    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xffffffff)

    png = b"\x89PNG\r\n\x1a\n"
    png += chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(bytes(raw), 9))
    png += chunk(b"IEND", b"")
    path.write_bytes(png)

# Part 07: head/hair. Retail local bounds are roughly 253x236x281.
v=[]; t=[]; uv=[]
add_uv_sphere(v,t,uv,(2,52,-3),(78,95,74),12,20,(0.02,0.05,0.48,0.95))
add_uv_sphere(v,t,uv,(2,-18,-1),(58,52,60),7,16,(0.02,0.05,0.48,0.95))
for bc,d,r,l in [
    ((0,125,0),(0,1,0),34,58),
    ((-38,115,-16),(-0.75,0.55,-0.15),24,66),
    ((38,116,-18),(0.75,0.55,-0.15),24,66),
    ((-62,92,8),(-0.9,0.15,0.15),22,58),
    ((63,92,8),(0.9,0.15,0.15),22,58),
    ((-40,135,30),(-0.45,0.65,0.62),21,55),
    ((40,135,30),(0.45,0.65,0.62),21,55),
    ((0,142,45),(0.0,0.55,0.83),24,52),
    ((-24,132,-42),(-0.25,0.55,-0.8),20,52),
    ((24,132,-42),(0.25,0.55,-0.8),20,52),
]:
    add_cone(v,t,uv,bc,d,r,l,8,(0.76,0.50))
write_obj(OUT/"part_07.obj",v,t,uv,"dart_hd_head_hair")

def head_rows(y):
    def pixel(x):
        if x >= 256:
            return (218,171,58) if x > 340 else (188,139,42)
        shade = int(12*y/511)
        return (min(255,202+shade//2), min(255,153+shade//3), min(255,112+shade//4))
    return pixel
write_solid_png(OUT/"part_07_albedo.png",512,512,head_rows)
write_solid_png(OUT/"part_07_material.png",16,16,lambda y: lambda x:(170,0,80))

# Part 02: core torso / armor.
v=[]; t=[]; uv=[]
add_box(v,t,uv,(116,-2,0),(112,82,78),(0.80,0.75),0.78)
add_box(v,t,uv,(100,14,-75),(100,62,18),(0.20,0.25),0.82)
add_box(v,t,uv,(100,14,75),(100,62,18),(0.20,0.25),0.82)
add_uv_sphere(v,t,uv,(-4,28,-72),(54,52,48),7,12,(0.02,0.02,0.45,0.45))
add_uv_sphere(v,t,uv,(-4,28,72),(54,52,48),7,12,(0.02,0.02,0.45,0.45))
add_box(v,t,uv,(86,30,0),(76,48,84),(0.22,0.30),0.72)
add_box(v,t,uv,(183,-70,0),(68,23,83),(0.78,0.75),0.95)
write_obj(OUT/"part_02.obj",v,t,uv,"dart_hd_torso")

def torso_rows(y):
    def pixel(x):
        if x < 256:
            return (192,43,34) if y < 180 else (151,26,24)
        if x > 360:
            return (70,53,42)
        return (37,38,44)
    return pixel
write_solid_png(OUT/"part_02_albedo.png",512,512,torso_rows)
write_solid_png(OUT/"part_02_material.png",16,16,lambda y: lambda x:(92,45,150))

(OUT/"MILESTONE.txt").write_text(
    "Dart head/torso geometry milestone v2\n"
    "Packaged automatically by the development workflow.\n"
    "Replacement parts: 02 and 07.\n",
    encoding="utf-8",
)

print("Generated Dart milestone assets in", OUT)
