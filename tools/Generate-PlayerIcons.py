"""Generate the same fixed Lucide geometry for Android and Windows."""
from pathlib import Path
import math
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
ICONS = {'play': 'play', 'pause': 'pause', 'restart': 'rotate-ccw',
         'back': 'rotate-ccw', 'forward': 'rotate-cw', 'audio': 'volume-2',
         'subtitles': 'captions', 'more': 'ellipsis', 'close': 'x',
         'fullscreen': 'maximize', 'next': 'skip-forward'}

def number(value):
    if abs(value) < 0.0000005:
        value = 0
    return f'{value:.6f}'.rstrip('0').rstrip('.') or '0'

def arc(start, end, rx, ry, degrees, large, sweep):
    """SVG endpoint arc, split into cubic curves of at most 90 degrees."""
    if start == end:
        return []
    rx, ry = abs(rx), abs(ry)
    if not rx or not ry:
        return [('L', *start, *end)]
    angle = math.radians(degrees % 360)
    ca, sa = math.cos(angle), math.sin(angle)
    dx, dy = (start[0]-end[0])/2, (start[1]-end[1])/2
    x, y = ca*dx+sa*dy, -sa*dx+ca*dy
    ratio = x*x/(rx*rx)+y*y/(ry*ry)
    if ratio > 1:
        factor = math.sqrt(ratio)
        rx, ry = rx*factor, ry*factor
    denominator = rx*rx*y*y+ry*ry*x*x
    factor = math.sqrt(max(0, (rx*rx*ry*ry-denominator)/denominator)) if denominator else 0
    if bool(large) == bool(sweep):
        factor = -factor
    cxp, cyp = factor*rx*y/ry, -factor*ry*x/rx
    cx = ca*cxp-sa*cyp+(start[0]+end[0])/2
    cy = sa*cxp+ca*cyp+(start[1]+end[1])/2
    first = math.atan2((y-cyp)/ry, (x-cxp)/rx)
    last = math.atan2((-y-cyp)/ry, (-x-cxp)/rx)
    delta = (last-first) % (2*math.pi)
    if not sweep and delta > 0:
        delta -= 2*math.pi
    count = max(1, math.ceil(abs(delta)/(math.pi/2)))
    step = delta/count
    def point(t):
        return (cx+rx*ca*math.cos(t)-ry*sa*math.sin(t), cy+rx*sa*math.cos(t)+ry*ca*math.sin(t))
    def tangent(t):
        return (-rx*ca*math.sin(t)-ry*sa*math.cos(t), -rx*sa*math.sin(t)+ry*ca*math.cos(t))
    result, previous = [], start
    for i in range(count):
        a, b = first+i*step, first+(i+1)*step
        p, q, tp, tq = point(a), point(b), tangent(a), tangent(b)
        k = 4/3*math.tan(step/4)
        target = end if i == count-1 else q
        result.append(('C', *previous, p[0]+k*tp[0], p[1]+k*tp[1], q[0]-k*tq[0], q[1]-k*tq[1], *target))
        previous = target
    return result

def geometry(data):
    tokens = re.findall(r'[A-Za-z]|[-+]?(?:\d*\.\d+|\d+\.?\d*)(?:[eE][-+]?\d+)?', data)
    result, index, command, previous_command = [], 0, None, None
    position = start = (0., 0.)
    control = None
    while index < len(tokens):
        if tokens[index].isalpha():
            command = tokens[index]
            index += 1
        upper, relative = command.upper(), command.islower()
        if upper == 'Z':
            result.append(('Z',))
            position, control, previous_command = start, None, upper
            command = None
            continue
        count = {'M':2, 'L':2, 'H':1, 'V':1, 'C':6, 'S':4, 'Q':4, 'T':2, 'A':7}[upper]
        values = list(map(float, tokens[index:index+count]))
        index += count
        def point(x, y):
            return (x+position[0], y+position[1]) if relative else (x, y)
        reflected = (2*position[0]-control[0], 2*position[1]-control[1]) if control is not None else position
        if upper in ('M', 'L'):
            target = point(*values)
            if upper == 'M':
                result.append(('M', *target))
                start = target
                command = 'l' if relative else 'L'
            else:
                result.append(('L', *position, *target))
            position, control = target, None
        elif upper in ('H', 'V'):
            target = (values[0]+(position[0] if relative else 0), position[1]) if upper == 'H' else (position[0], values[0]+(position[1] if relative else 0))
            result.append(('L', *position, *target))
            position, control = target, None
        elif upper in ('C', 'S'):
            first = point(*values[:2]) if upper == 'C' else (reflected if previous_command in ('C', 'S') else position)
            second, target = point(*values[-4:-2]), point(*values[-2:])
            result.append(('C', *position, *first, *second, *target))
            position, control = target, second
        elif upper in ('Q', 'T'):
            quadratic = point(*values[:2]) if upper == 'Q' else (reflected if previous_command in ('Q', 'T') else position)
            target = point(*values[-2:])
            first = tuple(position[j]+2/3*(quadratic[j]-position[j]) for j in (0, 1))
            second = tuple(target[j]+2/3*(quadratic[j]-target[j]) for j in (0, 1))
            result.append(('C', *position, *first, *second, *target))
            position, control = target, quadratic
        else:
            target = point(*values[-2:])
            result += arc(position, target, *values[:5])
            position, control = target, None
        previous_command = upper
    return result

def shape(element):
    kind = element.tag.split('}')[-1]
    if kind == 'path':
        return geometry(element.attrib['d'])
    if kind == 'circle':
        x, y, r = (float(element.attrib[k]) for k in ('cx', 'cy', 'r'))
        return geometry(f'M{x-r} {y} a{r} {r} 0 1 0 {2*r} 0 a{r} {r} 0 1 0 {-2*r} 0 Z')
    if kind == 'rect':
        x, y = float(element.get('x', 0)), float(element.get('y', 0))
        w, h = float(element.get('width')), float(element.get('height'))
        r = min(float(element.get('rx', 0)), w/2, h/2)
        if not r:
            return geometry(f'M{x} {y} h{w} v{h} h{-w} Z')
        return geometry(f'M{x+r} {y} h{w-2*r} a{r} {r} 0 0 1 {r} {r} v{h-2*r} a{r} {r} 0 0 1 {-r} {r} h{-w+2*r} a{r} {r} 0 0 1 {-r} {-r} v{-h+2*r} a{r} {r} 0 0 1 {r} {-r} Z')
    if kind == 'line':
        return geometry('M'+element.get('x1')+' '+element.get('y1')+' L'+element.get('x2')+' '+element.get('y2'))
    if kind in ('polygon', 'polyline'):
        points = re.findall(r'[-+]?[\d.]+', element.get('points'))
        return geometry('M'+' '.join(points[:2])+' L'+' '.join(points[2:])+(' Z' if kind == 'polygon' else ''))
    raise ValueError(kind)

def android_data(records):
    return ' '.join(command+','.join(number(v) for v in (values[2:] if command in ('L', 'C') else values)) for command, *values in records)

def emit():
    parts = ['// Generated from pinned Lucide SVGs (ISC) and Material Symbols digits (Apache 2.0).',
             'using System.Collections.Generic;', 'using System.Drawing;', 'using System.Drawing.Drawing2D;',
             'namespace Pluxy {', '    internal static class PlayerGlyphs {',
             '        private sealed class Layer { internal GraphicsPath Path; internal bool Fill; internal float Stroke; }',
             '        private static readonly Dictionary<string,Layer[]> Paths=Create();',
             '        internal static void Draw(Graphics graphics,string name,Color color) {',
             '            foreach(var layer in Paths[name]) {',
             '                if(layer.Fill) using(var brush=new SolidBrush(color)) graphics.FillPath(brush,layer.Path);',
             '                if(layer.Stroke>0) using(var pen=new Pen(color,layer.Stroke) { StartCap=LineCap.Round,EndCap=LineCap.Round,LineJoin=LineJoin.Round }) graphics.DrawPath(pen,layer.Path);',
             '            }', '        }', '        private static Dictionary<string,Layer[]> Create() {',
             '            var result=new Dictionary<string,Layer[]>();', '            var layers=new List<Layer>();', '            GraphicsPath path;']
    material = ET.parse(ROOT/'assets/player-icons/replay_10.svg').getroot()[0].get('d')
    # The digits precede the replay arrow and stay vector paths, never text.
    digits = geometry(material.split('M480-80')[0])
    digits = [tuple([record[0]]+[v*.022+(1.88 if i%2 == 0 else 22.68) for i, v in enumerate(record[1:])]) for record in digits]
    for symbol, name in ICONS.items():
        root = ET.parse(ROOT/f'assets/player-icons/lucide/{name}.svg').getroot()
        assert root.get('viewBox') == '0 0 24 24'
        primary = symbol in ('play', 'pause')
        layers = [(shape(element), primary, .7 if primary else 1.75) for element in root]
        if symbol in ('back', 'forward'):
            layers.append((digits, True, 0))
        xml = '<?xml version="1.0" encoding="utf-8"?>\n<!-- Lucide ISC; seek digits Material Symbols Apache 2.0. -->\n<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="28dp" android:height="28dp" android:viewportWidth="24" android:viewportHeight="24">\n'
        for records, fill, stroke in layers:
            attributes = f'android:fillColor="{("#FFFFFFFF" if fill else "#00000000")}"'
            if stroke:
                attributes += f' android:strokeColor="#FFFFFFFF" android:strokeWidth="{number(stroke)}" android:strokeLineCap="round" android:strokeLineJoin="round"'
            xml += f'    <path {attributes} android:pathData="{android_data(records)}" />\n'
            parts.append('            path=new GraphicsPath(FillMode.Winding);')
            for command, *values in records:
                if command == 'M':
                    parts.append('            path.StartFigure();')
                elif command == 'Z':
                    parts.append('            path.CloseFigure();')
                else:
                    method = 'AddLine' if command == 'L' else 'AddBezier'
                    parts.append('            path.'+method+'('+','.join(number(v)+'f' for v in values)+');')
            parts.append('            layers.Add(new Layer { Path=path,Fill='+str(fill).lower()+',Stroke='+number(stroke)+'f });')
        xml += '</vector>\n'
        (ROOT/f'client/app/src/main/res/drawable/ic_player_symbol_{symbol}.xml').write_text(xml, encoding='utf-8')
        parts += [f'            result.Add("{symbol}",layers.ToArray());', '            layers.Clear();']
    parts += ['            return result;', '        }', '    }', '}']
    (ROOT/'desktop/PlayerGlyphs.cs').write_text('\n'.join(parts)+'\n', encoding='utf-8')
    print('Generated', len(ICONS), 'shared Lucide pictograms.')

if __name__ == '__main__':
    emit()
