# Linear region format, version 1

A Linear file stores the chunks of one Minecraft region (32 x 32 chunks), like an Anvil `.mca` file. It is an
append-only log: a changed chunk is appended as a new record, a new index is appended after the records, and one of
two fixed slots at the start of the file is pointed at the new index. Superseded records are garbage until the file is
compacted (rewritten without them).

This document is normative. "Must" is a requirement for every reader and writer; "should" is a recommendation.

Files from earlier formats named Linear (xymb's Linear, DivineMC's Linear V1/V2/V3 and Buffered `.b_linear`) are not
this format. Readers must reject them (see "Recognising files"); converting them is the job of the converter tool.

## Conventions

- All integers are big-endian and unsigned unless written `i64` (two's complement).
- `xxh64(bytes)` is XXH64 with seed 0. A checksum field holds the full 64-bit value.
- Offsets are absolute byte offsets from the start of the file.
- `zstd` means one complete Zstandard frame (RFC 8878). Frames should not carry a content checksum (records carry
  their own); readers must accept frames with or without it.

## File name and location

`r.<regionX>.<regionZ>.linear`, in the same folders where Anvil keeps `r.<regionX>.<regionZ>.mca`: `region`,
`entities` and `poi` of every dimension. Chunk `(chunkX, chunkZ)` belongs to region `(chunkX >> 5, chunkZ >> 5)` and
has local index `(chunkX & 31) + (chunkZ & 31) * 32`, the same order as Anvil.

## Layout

```
offset 0    file header        32 bytes
offset 32   slot A             32 bytes
offset 64   slot B             32 bytes
offset 96   log: records and indexes, in the order they were written
```

### File header (32 bytes)

| Offset | Size | Field | Value |
|---|---|---|---|
| 0 | 8 | magic | `89 4C 49 4E 0D 0A 1A 0A` (`\x89LIN\r\n\x1a\n`) |
| 8 | 2 | major version | `1` |
| 10 | 2 | minor version | `0` |
| 12 | 4 | flags | `0`; no flags are defined in 1.0. Readers must ignore bits they do not know. |
| 16 | 4 | region X | `i32`, from the file name |
| 20 | 4 | region Z | `i32`, from the file name |
| 24 | 8 | header checksum | `xxh64` of bytes 0..24 |

The header is written once, when the file is created, and never changes; its minor version records the version the
file was created with and nothing else. A reader should warn when the region coordinates do not match the file name;
it must still read the file.

### Slot (32 bytes, two of them)

| Offset | Size | Field |
|---|---|---|
| 0 | 8 | generation; `0` means the slot is empty |
| 8 | 8 | index offset |
| 16 | 4 | index length (header and body) |
| 20 | 4 | reserved, `0` |
| 24 | 8 | slot checksum: `xxh64` of the slot's bytes 0..24 |

A slot is **valid** when its generation is not 0, its checksum matches, and the index it points to lies inside the
file and is valid (see below) and carries the same generation.

### Chunk record

| Offset | Size | Field |
|---|---|---|
| 0 | 4 | magic `4C 4E 43 52` (`LNCR`) |
| 4 | 2 | local chunk index, 0..1023 |
| 6 | 2 | flags, `0`; readers must ignore bits they do not know |
| 8 | 4 | dictionary ID; `0` means no dictionary |
| 12 | 4 | compressed length `C` |
| 16 | 4 | raw length `R` |
| 20 | 8 | timestamp, `i64` milliseconds since the Unix epoch, when the chunk was written |
| 28 | 8 | payload checksum: `xxh64` of the `C` payload bytes |
| 36 | `C` | payload: zstd frame of the chunk's NBT, compressed with the named dictionary |

The dictionary ID of the record decides which dictionary decompresses the payload. The zstd frame may carry its own
Dictionary_ID or omit it; when it carries one that differs from the record's, the chunk is corrupt.

The NBT is the chunk's root compound exactly as the server serialises it (the bytes an Anvil chunk holds after
decompression). `R` must equal the frame's decompressed size. A record is `36 + C` bytes long.

### Index

| Offset | Size | Field |
|---|---|---|
| 0 | 4 | magic `4C 4E 49 58` (`LNIX`) |
| 4 | 8 | generation |
| 12 | 4 | body raw length, always `20480` in 1.0 |
| 16 | 4 | body compressed length `B` |
| 20 | 8 | body checksum: `xxh64` of the `B` body bytes |
| 28 | `B` | body: zstd frame, no dictionary |

The decompressed body is 1024 entries of 20 bytes, in local index order:

| Offset | Size | Field |
|---|---|---|
| 0 | 8 | record offset; `0` means the chunk does not exist |
| 8 | 4 | record length (`36 + C`) |
| 12 | 8 | timestamp, copied from the record |

An index is **valid** when its magic and body checksum match, the body decompresses to exactly 20480 bytes, and every
present entry's record lies inside the file between offset 96 and the index offset.

## Reading

### Recognising files

1. A file shorter than 96 bytes holds no chunks: it was cut off while being created. Readers must treat it as an
   empty region; writers rewrite it from scratch.
2. Bytes 0..8 equal to `C3 FF 13 18 3C CA 9D 9A` mark a legacy Linear file (xymb Linear, DivineMC Linear V1/V2/V3).
   Readers must refuse it with an error saying the file has to be converted.
3. Any other magic, a wrong header checksum, or a major version above the one the reader implements: the reader must
   refuse the file. A higher minor version with the same major version must be readable.

### Opening

1. Read both slots. Discard every slot that is not valid.
2. If no slot is valid, the region is empty. If a slot had a non-zero generation but was invalid, the reader should
   log that it fell back.
3. Otherwise the valid slot with the highest generation is current, and its index is the region's state.
   If two valid slots carry the same generation the file is corrupt and must be refused.

Step 2 gives an empty region only when the log holds nothing a reader could lose. When no slot is valid but the file
is longer than 96 bytes, readers must not treat the region as empty: either refuse it or salvage it.

### Salvage

Salvage rebuilds a state from the log alone, for files whose slots are both lost. It is the expected outcome of a crash
during the first flush of a new file, and the last resort for damaged slots.

1. Scan the log from offset 96. At each position, a record is accepted when its magic is `LNCR`, its local index is
   below 1024, its lengths fit the limits and the file, and its payload checksum matches; the scan then continues
   after it. Otherwise the scan moves one byte forward. Index blocks (magic `LNIX`) are skipped by their length when
   their checksum matches.
2. For each local index the accepted record with the greatest timestamp wins; a later position in the file wins a
   tie.
3. The result is the region's state. Deletions are not recorded in records, so a chunk deleted before the damage can
   come back.

A writer that salvages a file should keep the original bytes (DivineMC renames it to `<file name>.corrupt`) and then
publish the salvaged state as a new file, as compaction does.

Readers must not trust anything past the end of the current index: bytes there are a write that was cut off, or a
newer state whose slot did not reach the disk.

### Reading a chunk

1. Take the chunk's entry from the current index. Offset `0` means the chunk does not exist.
2. Read `length` bytes at `offset`. The record's magic and local index must match, `36 + C` must equal the entry's
   length, and the payload checksum must match.
3. Decompress the payload with the dictionary named by the record. An unknown dictionary ID is an error naming the ID.
   The result must be `R` bytes.

A failed check makes that chunk unreadable; it must be reported as an error for that chunk, not as a missing chunk.
Other chunks stay readable.

## Writing

A writer owns the file exclusively; two writers must never have one file open.

### Creating a file

Write the header and two zeroed slots (96 bytes), make them durable, and make the directory durable where the platform
supports it (otherwise the new file itself can vanish in a crash on ext4 or XFS), all before the first flush.

### Flush (publishing a new state)

Given the current state (generation `g`, its index, end of the file `E`) and a set of changed chunks:

1. Append every changed chunk as a record at `E` and onward. A chunk changed twice since the last flush is written
   once, with its latest data. A deleted chunk gets no record.
2. Append the new index with generation `g + 1`: entries of changed chunks point at the new records, deleted chunks are
   `0`, all others are copied.
3. Make the appended bytes durable (fsync).
4. Write the slot that does **not** hold generation `g` (on the first flush of a file, slot A) with generation `g + 1`,
   the new index's offset and length.
5. Make the slot durable (fsync).

The new state is published at step 5. A crash at any earlier point leaves generation `g` current. Writers must only
append at the end of the file and must never overwrite bytes of the log; the only bytes rewritten in place are slots.
After a crash, bytes past the last valid index are garbage and stay in the file until compaction.

### Compaction

1. Write a new file next to the old one, named `<file name>.compact` : the header, slot A with generation `g + 1`, slot
   B empty, every live record of the current state copied byte for byte (in local index order, so neighbouring
   chunks are close on disk), and an index with generation `g + 1`.
2. Make the new file durable, then rename it over the old file (atomic replace), then make the directory durable
   where the platform supports it.

If any step fails the old file stays as it was. A leftover `.compact` file is garbage and may be deleted when the
region is opened.

Windows refuses to replace a file that is open without `FILE_SHARE_DELETE`, which includes the writer's own handle:
writers close their handle before the rename and reopen afterwards, and keep the old file when the rename keeps failing
(another program may hold it).

### Other programs reading a live file

Map renderers, backup tools and editors read region files while the server runs. Such a reader opens the file once,
reads the slots and follows that index through the same handle. Appends never touch the bytes an index points at, and
on POSIX systems a compaction replaces the directory entry while the open handle keeps the old file. A reader that
reopens the file between reading the slots and reading records must start over from the slots.

The garbage of a file is its length minus 96 minus the lengths of all live records and of the current index. When to
compact is up to the writer (see "Recommendations").

## Dictionaries

Records may be compressed with a zstd dictionary. A dictionary is identified by a 32-bit ID; `0` means none, and the
IDs below are assigned by this document. Every reader and writer ships the same dictionaries, byte for byte; a reader
must check each shipped dictionary against its SHA-256 below. The dictionary ID inside the dictionary file (bytes 4..8,
little-endian, as zstd defines it) equals the ID in this table.

A dictionary is added for each Minecraft version whose chunk data changes, trained on chunks of that version. IDs are
never reused or removed: files keep every dictionary they were written with readable forever. A writer should use the
newest dictionary for the data type it writes; chunks move to it when they are next saved.

| ID | File | Data | Trained on | SHA-256 |
|---|---|---|---|---|
| 1 | `dicts/1-region-26.3.zdict` | `region` | Minecraft 26.3 | see `dicts/SHA256SUMS` |
| 2 | `dicts/2-entities-26.3.zdict` | `entities` | Minecraft 26.3 | see `dicts/SHA256SUMS` |
| 3 | `dicts/3-poi-26.3.zdict` | `poi` | Minecraft 26.3 | see `dicts/SHA256SUMS` |

## Limits

- `R` (raw chunk size) must not exceed 536870912 (512 MiB). Readers must reject larger values before allocating
  memory. There is no smaller limit: chunks larger than Anvil's 1 MiB sector limit are ordinary records, and no
  external `.mcc` files exist in this format.
- `C` must not exceed `R + R / 128 + 1024`.
- A file must not exceed 2^63 - 1 bytes.

## Versioning

- The minor version increases for additions old readers can ignore, such as a header or record flag that only
  describes the data. Readers ignore unknown flags and accept any minor version of their major version.
- The major version increases for anything else. A reader refuses a major version it does not implement.
- Dictionaries are not versioned with the format. A new dictionary is a new ID in the table above; a reader that does
  not have it reports the chunks using it as unreadable, naming the ID.

## Recommendations for writers

These are not part of the format; they describe what DivineMC does.

- Flush a region about 10 seconds after its first unflushed change, and synchronously when the server asks for a
  flush (`save-all flush`, shutdown).
- Compact when garbage exceeds the live data after a flush or when a region is opened, when a region leaves the region
  cache with garbage above a quarter of the live data, and on shutdown when garbage exceeds the live data. Compaction
  runs on its own thread: reads go on during it, only the final swap blocks them.
- Compress with zstd level 6 and the newest dictionary for the data type.

## Golden files

`golden/` holds files every implementation must read to the contents listed in `golden/manifest.json`. They are
produced by the reference implementation in DivineMC and checked in CI by every implementation.
