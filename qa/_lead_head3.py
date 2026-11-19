import numpy as np
from PIL import Image
a = np.asarray(Image.open("qa/shots/match_list_v4.png").convert("RGB")).astype(np.int16)
R,G,B = a[:,:,0],a[:,:,1],a[:,:,2]
orange=(R>230)&(G>80)&(G<180)&(B<140)&(R-B>110)
graytxt=(np.abs(R-138)<25)&(np.abs(G-143)<25)&(np.abs(B-168)<25)
def bb(mask,x0,x1,y0,y1,tag):
    sub=mask[y0:y1,x0:x1]; ys,xs=np.where(sub)
    if not len(ys): print(f"  {tag}: 无"); return None
    Y0,Y1=y0+ys.min(),y0+ys.max(); print(f"  {tag}: y[{Y0},{Y1}] h={Y1-Y0+1}px={((Y1-Y0+1)/3.5):.2f}dp 中心={((Y0+Y1)/2):.1f} 距卡顶={(((Y0+Y1)/2)-1133)/3.5:.2f}dp x[{x0+xs.min()},{x0+xs.max()}]")
    return (Y0+Y1)/2
for top,tag in [(1133,"卡2"),(1545,"卡3"),(1957,"卡4")]:
    print(f"--- {tag} top={top} ---")
    y0,y1=top+30,top+95
    c1=bb(orange,50,200,y0,y1,"联赛胶囊(橙)")
    c2=bb(graytxt,180,330,y0,y1,"编号(灰蓝)")
    # 时间在右侧
    c3=bb(graytxt,700,1000,y0,y1,"时间(灰蓝)")
    if c1 and c2: print(f"  ⇒ 胶囊中心 - 编号中心 = {c1-c2:+.1f}px = {(c1-c2)/3.5:+.2f}dp")
    if c2 and c3: print(f"  ⇒ 编号中心 - 时间中心 = {c2-c3:+.1f}px")
