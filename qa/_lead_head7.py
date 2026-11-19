# -*- coding: utf-8 -*-
from PIL import Image
files = {
 "用户08:36": r"C:/Users/wanghongbo/.workbuddy/clipboard-images/clipboard-2026-10-16T08-36-03-926Z-7d93cf04.png",
 "我方卡v4":  r"qa/shots/match_card_v4.png",
}
for tag,p in files.items():
    try:
        im = Image.open(p).convert("RGB")
    except Exception as e:
        print(tag, "ERR", e); continue
    W,H = im.size
    px = im.load()
    def lum(c): return (c[0]*299+c[1]*587+c[2]*114)//1000
    print(f"--- {tag}  size={W}x{H} ---")
    # 1) 找橙色渐变胶囊（R>240, 90<G<190, B<200, 且 G-B<60）
    xs=[];ys=[]
    for y in range(H):
        for x in range(W):
            r,g,b = px[x,y]
            if r>235 and 95<g<195 and b<200 and (g-b)<70:
                xs.append(x); ys.append(y)
    if not xs: print("  未找到橙色胶囊"); continue
    x0,x1,y0,y1 = min(xs),max(xs),min(ys),max(ys)
    print(f"  胶囊 bg bbox x[{x0},{x1}] w={x1-x0+1} y[{y0},{y1}] h={y1-y0+1} 中心y={(y0+y1)/2:.1f}")
    # 2) 胶囊内白字 ink
    ix=[];iy=[]
    for y in range(y0,y1+1):
        for x in range(x0,x1+1):
            c=px[x,y]
            if lum(c)>215 and c[2]>200:
                ix.append(x); iy.append(y)
    if iy:
        print(f"  胶囊内白字 ink y[{min(iy)},{max(iy)}] h={max(iy)-min(iy)+1} 中心y={(min(iy)+max(iy))/2:.1f}"
              f" | 上留白={min(iy)-y0} 下留白={y1-max(iy)}")
    # 3) 灰色文字（编号/时间）ink: 在胶囊右侧找 100<lum<200 的冷色像素
    gx=[];gy=[]
    for y in range(max(0,y0-20), min(H,y1+20)):
        for x in range(x1+1, min(W, x1+400)):
            c=px[x,y]
            if 110<lum(c)<190 and c[2]>=c[0]:
                gx.append(x); gy.append(y)
    if gy:
        print(f"  灰冷色字 ink (x>{x1}) y[{min(gy)},{max(gy)}] 中心y={(min(gy)+max(gy))/2:.1f}"
              f"  → 相对胶囊中心偏移 {(min(gy)+max(gy))/2-(y0+y1)/2:+.1f}px")
