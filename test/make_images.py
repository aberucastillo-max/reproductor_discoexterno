#!/usr/bin/env python3
"""Crea imágenes NTFS de prueba con ntfs-3g y genera el listado de referencia.

Uso: make_images.py DIR_SALIDA
Para cada imagen deja: <nombre>.img, <nombre>.bs (tamaño de bloque) y <nombre>.ref (listado esperado).
"""
import hashlib
import os
import random
import subprocess
import sys

OUT = sys.argv[1]
MNT = os.path.join(OUT, "mnt")
os.makedirs(MNT, exist_ok=True)
rnd = random.Random(1234)


def sh(*cmd):
    subprocess.run(cmd, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def rbytes(n):
    return rnd.randbytes(n)


def populate_basic(root, compression):
    os.makedirs(f"{root}/Fotos/Vacaciones 2024", exist_ok=True)
    os.makedirs(f"{root}/Música/Ñandú ünïcödé 😀", exist_ok=True)
    with open(f"{root}/pequeño.txt", "wb") as f:      # residente en la MFT
        f.write("hola desde NTFS\n".encode())
    open(f"{root}/vacío.bin", "wb").close()
    with open(f"{root}/Fotos/grande.bin", "wb") as f:
        f.write(rbytes(9 * 1024 * 1024 + 123))
    for i in range(40):
        with open(f"{root}/Fotos/Vacaciones 2024/IMG_{i:04d}.jpg", "wb") as f:
            f.write(rbytes(rnd.randint(1, 200_000)))
    with open(f"{root}/Música/Ñandú ünïcödé 😀/canción.mp3", "wb") as f:
        f.write(rbytes(777_777))
    # carpeta con muchos archivos -> índice grande en varios niveles
    os.makedirs(f"{root}/MuchosArchivos")
    for i in range(3000):
        with open(f"{root}/MuchosArchivos/archivo_con_nombre_largo_{i:05d}.dat", "wb") as f:
            f.write(rbytes(i % 50))
    # archivo disperso (sparse)
    with open(f"{root}/disperso.vhd", "wb") as f:
        f.write(b"INICIO")
        f.seek(20 * 1024 * 1024)
        f.write(b"MEDIO" * 1000)
        f.truncate(40 * 1024 * 1024)
    # dos archivos que crecen alternados -> muy fragmentados -> $ATTRIBUTE_LIST
    a = open(f"{root}/frag_a.bin", "wb", buffering=0)
    b = open(f"{root}/frag_b.bin", "wb", buffering=0)
    for _ in range(2500):
        a.write(rbytes(4096))
        b.write(rbytes(4096))
    a.close()
    b.close()
    if compression:
        os.makedirs(f"{root}/Comprimida")
        os.setxattr(f"{root}/Comprimida", "system.ntfs_attrib_be", (0x800).to_bytes(4, "big"))
        with open(f"{root}/Comprimida/texto.txt", "wb") as f:
            for i in range(60000):
                f.write(f"Línea {i} texto repetitivo que se comprime muy bien\n".encode())
        with open(f"{root}/Comprimida/aleatorio.bin", "wb") as f:
            f.write(rbytes(300_000))
        with open(f"{root}/Comprimida/mixto.bin", "wb") as f:
            f.write(b"\0" * 200_000 + rbytes(50_000) + b"ABC" * 70_000)


def populate_many(root):
    # Muchos archivos intercalados con datos -> la MFT crece fragmentada
    for d in range(20):
        os.makedirs(f"{root}/d{d}")
        for i in range(1000):
            with open(f"{root}/d{d}/f{i}.txt", "wb") as f:
                f.write(rbytes(800))
        with open(f"{root}/d{d}/relleno.bin", "wb") as f:
            f.write(rbytes(3 * 1024 * 1024))


def populate_frag(root):
    # Huecos alternos -> un archivo grande queda en miles de trozos -> $ATTRIBUTE_LIST
    os.makedirs(f"{root}/tmp")
    for i in range(6000):
        with open(f"{root}/tmp/{i}", "wb") as f:
            f.write(rbytes(8192))
    st = os.statvfs(root)
    with open(f"{root}/relleno.bin", "wb") as f:   # llena el disco para forzar el uso de los huecos
        f.write(b"\xAA" * (st.f_bavail * st.f_frsize - 4 * 1024 * 1024))
    for i in range(0, 6000, 2):
        os.remove(f"{root}/tmp/{i}")
    with open(f"{root}/muy_fragmentado.bin", "wb") as f:
        f.write(rbytes(8192 * 2900 - 77))
    # muchos enlaces duros -> varios $FILE_NAME -> $ATTRIBUTE_LIST
    with open(f"{root}/original.txt", "wb") as f:
        f.write(b"enlazado\n" * 100)
    os.makedirs(f"{root}/enlaces")
    for i in range(60):
        os.link(f"{root}/original.txt", f"{root}/enlaces/enlace_con_un_nombre_bastante_largo_{i:03d}.txt")


def populate_names(root):
    files = [
        "Música/Shakira - Waka Waka.mp4", "Música/Shakira - Hips Don't Lie (Official Video).mp4",
        "Música/SHAKIRA - LA BICICLETA.avi", "Música/Bad Bunny ft. Shakira - Chantaje Remix.mp4",
        "Música/Bad Bunny - Tití Me Preguntó.mp4", "Música/Bad Bunny - Me Porto Bonito 1080p.mkv",
        "Música/bad bunny - safaera.mpg", "Música/Karol G & Bad Bunny - Ahora Me Llama.mp4",
        "Películas/Brad Pitt/Fight Club 1999.mkv", "Películas/Brad Pitt/Troya.avi", "Películas/Brad Pitt/Snatch.mp4",
        "Películas/Brad Pitt/entrevista.mp4", "Películas/Angelina Jolie Brad Pitt Mr Smith.mp4",
        "Videos/Entrevista Brad Pitt Cannes.mp4", "Videos/Angelina Jolie premios.mp4", "Videos/Angelina Jolie Salt.mpg",
        "DCIM/Camera/IMG_20240101_101010.jpg", "DCIM/Camera/IMG_20240102_101010.jpg", "DCIM/Camera/IMG_20240103_101010.jpg",
        "DCIM/Camera/VID_20240104_101010.mp4", "Fotos/Boda Ana y Luis/001.jpg", "Fotos/Boda Ana y Luis/002.jpg",
        "Fotos/Boda Ana y Luis/003.jpg", "Fotos/Boda Ana y Luis/004.jpg", "Documentos/factura.pdf", "Documentos/notas.txt",
    ]
    for i, f in enumerate(files):
        os.makedirs(os.path.dirname(f"{root}/{f}"), exist_ok=True)
        with open(f"{root}/{f}", "wb") as h:
            h.write(rbytes(1000 * (i + 1)))


def reference(root):
    lines = []
    for dp, dns, fns in os.walk(root):
        rel = dp[len(root):]
        for d in dns:
            lines.append(f"D {rel}/{d}")
        for fn in fns:
            p = os.path.join(dp, fn)
            h = hashlib.sha256(open(p, "rb").read()).hexdigest()
            lines.append(f"F {rel}/{fn} {os.path.getsize(p)} {h}")
    return sorted(lines)


def write_gpt(img, bs, start, count):
    """GPT mínima (MBR protector + cabecera + 1 entrada) para sectores lógicos != 512."""
    import struct, uuid, zlib
    total = os.path.getsize(img) // bs
    ent = bytearray(128 * 128)
    ent[0:16] = uuid.UUID("EBD0A0A2-B9E5-4433-87C0-68B6B72699C7").bytes_le
    ent[16:32] = uuid.uuid4().bytes_le
    struct.pack_into("<QQ", ent, 32, start, start + count - 1)
    hdr = bytearray(92)
    struct.pack_into("<8sIIIIQQQQ16sQIII", hdr, 0, b"EFI PART", 0x10000, 92, 0, 0, 1, total - 1,
                     34, total - 34, uuid.uuid4().bytes_le, 2, 128, 128, zlib.crc32(ent))
    struct.pack_into("<I", hdr, 16, zlib.crc32(hdr))
    mbr = bytearray(512)
    struct.pack_into("<BBBBBBBBII", mbr, 446, 0, 0, 2, 0, 0xEE, 0xFF, 0xFF, 0xFF, 1, min(total - 1, 0xFFFFFFFF))
    mbr[510:512] = b"\x55\xaa"
    with open(img, "r+b") as f:
        f.write(mbr)
        f.seek(bs)
        f.write(hdr)
        f.seek(2 * bs)
        f.write(ent)


def make(name, size_mb, mkntfs_args, layout, bs, populate, compression=False):
    img = os.path.join(OUT, name + ".img")
    part = img + ".part"
    lead = 1024 * 1024 if layout != "none" else 0
    with open(part, "wb") as f:
        f.truncate(size_mb * 1024 * 1024)
    sh("mkntfs", "-F", "-q", "-f", "-L", "PRUEBA_" + name, *mkntfs_args, part)
    opts = "compression" if compression else "nocompression"
    sh("ntfs-3g", "-o", opts, part, MNT)
    try:
        populate(MNT, compression) if populate is populate_basic else populate(MNT)
        ref = reference(MNT)
    finally:
        sh("umount", MNT)
    if layout == "none":
        os.replace(part, img)
    else:
        with open(img, "wb") as f:
            f.truncate(lead)
            f.seek(lead)
            with open(part, "rb") as p:
                while True:
                    chunk = p.read(1 << 20)
                    if not chunk:
                        break
                    f.write(chunk)
            f.write(b"\0" * (1024 * 1024))
        os.remove(part)
        start = lead // bs
        count = size_mb * 1024 * 1024 // bs
        if layout == "mbr":
            script = f"label: dos\nstart={start}, size={count}, type=7\n"
        else:
            script = f"label: gpt\nfirst-lba={start}\nstart={start}, size={count}, type=EBD0A0A2-B9E5-4433-87C0-68B6B72699C7\n"
        if bs == 512:
            subprocess.run(["sfdisk", "-q", img], input=script.encode(), check=True, stdout=subprocess.DEVNULL)
        else:
            write_gpt(img, bs, start, count)
    open(os.path.join(OUT, name + ".ref"), "w").write("\n".join(ref) + "\n")
    open(os.path.join(OUT, name + ".bs"), "w").write(str(bs))
    print("creada", name)


make("basico", 400, ["-c", "4096"], "none", 512, populate_basic, compression=True)
make("mbr512", 400, ["-c", "512", "-p", "2048"], "mbr", 512, populate_basic, compression=True)
make("gpt4k", 400, ["-s", "4096", "-c", "65536", "-p", "256"], "gpt", 4096, populate_basic)
make("muchos", 300, ["-c", "4096", "-p", "2048"], "mbr", 512, populate_many)
make("frag", 200, ["-c", "4096"], "none", 512, populate_frag)
make("nombres", 64, ["-c", "4096"], "none", 512, populate_names)
