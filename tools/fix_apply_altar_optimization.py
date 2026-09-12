from pathlib import Path
p = Path('tools/apply_altar_optimization.py')
s = p.read_text()
old = "h = h.replace('''                CHUNKS_TO_ENSURE.clear();\\n''', '')\nold_tick_chunk ="
if old not in s:
    raise RuntimeError('early CHUNKS_TO_ENSURE removal marker not found')
s = s.replace(old, "old_tick_chunk =", 1)
marker = 'h = replace_once(h, old_tick_chunk, new_tick_chunk, "hidden tick batch")\nchunk_methods_start ='
if marker not in s:
    raise RuntimeError('hidden tick replacement marker not found')
s = s.replace(
    marker,
    'h = replace_once(h, old_tick_chunk, new_tick_chunk, "hidden tick batch")\n'
    "h = h.replace('''                CHUNKS_TO_ENSURE.clear();\\n''', '')\n"
    'chunk_methods_start =',
    1,
)
p.write_text(s)
print('Fixed patch application order')
