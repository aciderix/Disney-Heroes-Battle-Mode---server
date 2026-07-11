#!/usr/bin/env python3
"""
Désassembleur ciblé de la lib native ARM d'origine (native/reference/libspine-native.so, non strippée)
pour EXTRAIRE fidèlement (pas inventer) : le format binaire `.np` (ParticleEffect::load + sous-parseur),
les structs TwoColorVertex / ParticleDrawCall (ParticleEffect::getTCVertices), la simulation
(ParticleEmitter::updateParticles). Conforme à PRINCIPLES §4 (extraction, jamais réécriture devinée).

Usage : python3 native/tools/disasm.py <symbole|adresse_hex> [nb_instructions]
Ex.   : python3 native/tools/disasm.py _ZN14ParticleEffect4loadEPhj
        python3 native/tools/disasm.py 0x1a770 120     # sous-parseur d'emitter .np
        python3 native/tools/disasm.py list            # liste les symboles utiles

Dépendances : pip install capstone pyelftools.
"""
import sys
from elftools.elf.elffile import ELFFile
from capstone import Cs, CS_ARCH_ARM, CS_MODE_THUMB

SO = __file__.rsplit('/', 2)[0] + '/reference/libspine-native.so'


def load():
    f = open(SO, 'rb')
    elf = ELFFile(f)
    syms = {}
    for sec in elf.iter_sections():
        if sec.name in ('.symtab', '.dynsym'):
            for s in sec.iter_symbols():
                if s.name and s['st_value']:
                    syms[s.name] = (s['st_value'], s['st_size'])
    secs = [(s['sh_addr'], s['sh_size'], s['sh_offset']) for s in elf.iter_sections() if s['sh_addr']]
    return f, syms, secs


def fileoff(secs, va):
    for a, sz, o in secs:
        if a <= va < a + sz:
            return o + (va - a)
    return None


def main():
    f, syms, secs = load()
    arg = sys.argv[1] if len(sys.argv) > 1 else 'list'
    if arg == 'list':
        for n in sorted(syms):
            if 'Particle' in n or 'Native_' in n or 'Skeleton' in n:
                a, s = syms[n]
                print(f"{a:#08x} {s:5d}  {n}")
        return
    limit = int(sys.argv[2]) if len(sys.argv) > 2 else 60
    if arg in syms:
        addr, size = syms[arg]
    else:
        addr = int(arg, 16); size = limit * 4
    base = addr & ~1
    off = fileoff(secs, base)
    f.seek(off); code = f.read(size)
    md = Cs(CS_ARCH_ARM, CS_MODE_THUMB)
    print(f"=== {arg}  addr={addr:#x} size={size} ===")
    for n, i in enumerate(md.disasm(code, base)):
        print(f"  {i.address:#08x}: {i.mnemonic:8} {i.op_str}")
        if n + 1 >= limit:
            break


if __name__ == '__main__':
    main()
