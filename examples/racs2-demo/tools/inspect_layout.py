"""Run by GDB against a header probe; never starts or attaches to a process."""
import gdb
import json
import os


def flatten(type_, path='', offset=0):
    original = str(type_)
    type_ = type_.strip_typedefs()
    size = int(type_.sizeof)
    if type_.code == gdb.TYPE_CODE_STRUCT:
        result = []
        for field in type_.fields():
            if field.bitsize or field.bitpos % 8:
                raise ValueError('Unsupported bit field: ' + path)
            result.extend(flatten(field.type, path + ('.' if path else '') + field.name,
                                  offset + int(field.bitpos) // 8))
        return result
    if type_.code == gdb.TYPE_CODE_ARRAY:
        element = type_.target().strip_typedefs()
        first, last = type_.range()
        if str(element) == 'char':
            return [dict(path=path, offset=offset, size=size, kind='string', c_type=original)]
        result = []
        for i in range(first, last + 1):
            result.extend(flatten(type_.target(), path + '[%d]' % i,
                                  offset + (i - first) * int(element.sizeof)))
        return result
    if type_.code == gdb.TYPE_CODE_FLT:
        kind = 'float'
    elif type_.code == gdb.TYPE_CODE_BOOL:
        kind = 'unsigned'
    elif type_.code in (gdb.TYPE_CODE_INT, gdb.TYPE_CODE_CHAR, gdb.TYPE_CODE_ENUM):
        kind = 'signed' if int(gdb.Value(-1).cast(type_)) < 0 else 'unsigned'
    else:
        raise ValueError('Unsupported C type: ' + original + ' at ' + path)
    return [dict(path=path, offset=offset, size=size, kind=kind, c_type=original,
                 boolean=type_.code == gdb.TYPE_CODE_BOOL)]


names = json.load(open(os.environ['CFS_XTCE_TYPE_LIST']))
result = {name: dict(size=int(gdb.lookup_type(name).sizeof), fields=flatten(gdb.lookup_type(name)))
          for name in names}
with open(os.environ['CFS_XTCE_LAYOUT_OUTPUT'], 'w') as stream:
    json.dump(result, stream, indent=2)
