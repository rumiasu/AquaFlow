"""Original 24-unit line geometry, exported as local PNGs for native miniapp image compatibility.
Regenerate with Python + Pillow. No downloaded artwork or runtime dependency.
"""
from pathlib import Path
from PIL import Image, ImageDraw
INK=(18,100,214,255);S=4;STROKE=1.65
ICONS={
 'orders':[('r',5,3,14,18,1),('l',8,8,16,8),('l',8,12,16,12),('l',8,16,13,16)],
 'products':[('r',8,7,8,14,2),('r',9,3,6,4,1),('l',8,12,16,12),('l',9,16,15,16)],
 'customers':[('c',9,7,3),('a',3,13,12,8,180,360),('a',14,4,6,6,270,90),('a',15,13,6,8,270,360)],
 'receivables':[('r',3,6,18,14,2),('l',5,6,17,3,18,6),('r',13,11,8,5,1),('c',16,13.5,.6)],
 'payments':[('r',4,4,16,16,2),('l',7,8,12,8),('l',7,12,10,12),('l',11,15,14,18,20,11)],
 'staff':[('r',5,5,14,16,2),('l',9,3,15,3),('c',12,10,2.5),('a',8,15,8,5,180,360)],
 'returns':[('r',9,9,9,12,2),('l',11,5,16,5,16,9),('l',8,4,4,8,8,12),('l',4,8,12,8)],
 'feedback':[('r',3,4,18,13,3),('l',6,17,6,21,11,17),('l',7,9,17,9),('l',7,13,13,13)],
 'exception':[('l',12,3,22,21,2,21,12,3),('l',12,9,12,14),('c',12,18,.7)],
 'waiting':[('r',4,4,13,16,2),('l',8,3,13,3),('l',7,9,13,9),('c',17,17,5),('l',17,14,17,17,19,18)],
 'claims':[('r',3,8,18,13,2),('l',3,8,7,3,17,3,21,8),('l',8,13,16,13),('l',12,11,12,17)],
 'adjust':[('l',4,6,20,6),('l',4,12,20,12),('l',4,18,20,18),('c',9,6,2),('c',16,12,2),('c',10,18,2)],
 'owed':[('r',4,7,11,14,2),('l',7,3,12,3,12,7),('l',5,12,14,12),('c',18,7,4),('l',16,7,20,7)],
 'station':[('l',3,10,12,3,21,10),('l',5,9,5,21,19,21,19,9),('r',10,14,4,7,0),('l',8,10,16,10)],
 'delivery':[('r',2,7,12,11,1),('l',14,10,18,10,22,14,22,18,14,18),('c',7,19,2),('c',18,19,2),('l',5,11,10,11)],
 'notice':[('l',4,10,16,5,16,18,4,14,4,10),('l',7,15,8,21,12,21,11,16),('l',20,8,22,7),('l',20,15,22,16)],
 'payroll':[('r',5,3,14,18,2),('r',8,6,8,3,0),('l',8,13,10,13),('l',14,13,16,13),('l',8,17,10,17),('l',14,17,16,17)],
 'settlement':[('r',3,4,7,7,1),('r',14,13,7,7,1),('l',13,7,20,7,20,10),('l',18,8,20,10,22,8),('l',11,17,4,17,4,14),('l',2,16,4,14,6,16)],
 'profit':[('l',4,3,4,21,22,21),('l',8,17,8,13),('l',13,17,13,10),('l',18,17,18,7),('l',7,10,13,5,18,5)],
 'dashboard':[('r',3,3,8,8,1),('r',15,3,6,5,1),('r',3,15,8,6,1),('r',15,12,6,9,1)]}
def render(shapes):
 im=Image.new('RGBA',(24*S,24*S));d=ImageDraw.Draw(im);w=round(STROKE*S)
 for shape in shapes:
  op,*a=shape
  if op=='r':
   x,y,width,height,r=a;d.rounded_rectangle((x*S,y*S,(x+width)*S,(y+height)*S),radius=r*S,outline=INK,width=w)
  elif op=='c':
   x,y,r=a;d.ellipse(((x-r)*S,(y-r)*S,(x+r)*S,(y+r)*S),outline=INK,width=w)
  elif op=='l':
   pts=list(zip(a[::2],a[1::2]));d.line([(x*S,y*S) for x,y in pts],fill=INK,width=w,joint='curve')
   for x,y in [pts[0],pts[-1]]:r=w/2;d.ellipse((x*S-r,y*S-r,x*S+r,y*S+r),fill=INK)
  elif op=='a':
   x,y,width,height,start,end=a;d.arc((x*S,y*S,(x+width)*S,(y+height)*S),start,end,fill=INK,width=w)
 return im.resize((72,72),Image.Resampling.LANCZOS)
def main():
 root=Path(__file__).resolve().parents[1];out=root/'miniapp-delivery/assets/management';out.mkdir(parents=True,exist_ok=True)
 for name,geometry in ICONS.items():render(geometry).save(out/(name+'.png'),optimize=True)
 print('MANAGEMENT_ICONS_OK '+str(len(ICONS)))
if __name__=='__main__':main()
