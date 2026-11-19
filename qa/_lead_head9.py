# -*- coding: utf-8 -*-
from PIL import Image
def lum(c): return (c[0]*299+c[1]*587+c[2]*114)//1000

def analyze(p, label):
    im = Image.open(p).convert("RGB"); W,H = im.size; px = im.load()
    print(f"=== {label}  {W}x{H} ===")
    # 1) 橙色渐变胶囊：R>235 且 100<G<200 且 70<B<165
    def isO(c): return c[0]>235 and 100<c[1]<200 and 70<c[2]<165
    def isR(c): return c[0]>225 and c[1]<105 and c[2]<120
    # 逐列统计
    colO = [sum(1 for y in range(H) if isO(px[x,y])) for x in range(W)]
    # 找第一段连续 colO>3 的 x 区间（胶囊）；允许 3px 空隙
    runs=[]; s=None; gap=0
    for x in range(W):
        if colO[x]>2:
            if s is None: s=x
            gap=0
        else:
            if s is not None:
                gap+=1
                if gap>4:
                    runs.append((s,x-gap)); s=None; gap=0
    if s is not None: runs.append((s,W-1))
    runs=[r for r in runs if r[1]-r[0]>25]
    print("  橙渐变段(宽>25px):", runs)
    for (a,b) in runs[:3]:
        ys=[y for y in range(H) for x in range(a,b+1) if isO(px[x,y])]
        y0,y1=min(ys),max(ys)
        # 框内白字 ink（限该框 x 范围内，向两侧各留 0）
        iy=[]
        for y in range(max(0,y0-6), min(H,y1+7)):
            for x in range(a,b+1):
                c=px[x,y]
                if lum(c)>228 and abs(c[0]-c[2])<26: iy.append(y)
        if iy:
            i0,i1=min(iy),max(iy)
            print(f"  胶囊 x[{a},{b}] w={b-a+1}px={round((b-a+1)/3.5,1)}dp | 框 y[{y0},{y1}] h={y1-y0+1}px={round((y1-y0+1)/3.5,1)}dp 中心={(y0+y1)/2:.1f}")
            print(f"      白字ink y[{i0},{i1}] h={i1-i0+1}px={round((i1-i0+1)/3.5,1)}dp 中心={(i0+i1)/2:.1f} | 上留白={i0-y0}px={round((i0-y0)/3.5,1)}dp 下留白={y1-i1}px={round((y1-i1)/3.5,1)}dp")
    # 2) 纯红 tag #FF4D4F（排除边框：取 x 在胶囊右侧 200px 内）
    if runs:
        a0 = runs[0][1]
        ys=[y for y in range(H) for x in range(a0+10, min(W,a0+320)) if isR(px[x,y])]
        if ys:
            y0,y1=min(ys),max(ys)
            xs=[x for y in range(y0,y1+1) for x in range(a0+10, min(W,a0+320)) if isR(px[x,y])]
            x0,x1=min(xs),max(xs)
            print(f"  红tag x[{x0},{x1}] w={x1-x0+1}px={(x1-x0+1)/3.5:.1f}dp | y[{y0},{y1}] h={y1-y0+1}px={(y1-y0+1)/3.5:.1f}dp 中心={(y0+y1)/2:.1f}")
            iy=[y for y in range(y0,y1+1) for x in range(x0,x1+1) if lum(px[x,y])>228 and abs(px[x,y][0]-px[x,y][2])<30]
            if iy:
                i0,i1=min(iy),max(iy)
                print(f"      白字ink y[{i0},{i1}] h={i1-i0+1}px | 上留白={i0-y0}px 下留白={y1-i1}px 中心={(i0+i1)/2:.1f}")
    # 3) 灰冷色文字（编号/时间）
    if runs:
        a0 = runs[0][1]
        gy=[y for y in range(H) for x in range(a0+8, W) if 110<lum(px[x,y])<195 and px[x,y][2]>=px[x,y][0]]
        gx=[x for y in range(H) for x in range(a0+8, W) if 110<lum(px[x,y])<195 and px[x,y][2]>=px[x,y][0]]
        if gy:
            print(f"  灰冷字 x[{min(gx)},{max(gx)}] y[{min(gy)},{max(gy)}] h={max(gy)-min(gy)+1}px 中心={(min(gy)+max(gy))/2:.1f}")
    print()

analyze("qa/shots/match_card_v4.png", "我方真机 单卡v4")
analyze(r"C:/Users/wanghongbo/.workbuddy/clipboard-images/clipboard-2026-10-16T08-36-03-926Z-7d93cf04.png", "用户08:36")
