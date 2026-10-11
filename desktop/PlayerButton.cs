using System;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Imaging;
using System.Runtime.InteropServices;
using System.Windows.Forms;

namespace Pluxy {
    internal sealed class PlayerButton : Button {
        public string Symbol;
        public bool StrongFocus;
        public bool TextOnly;
        public Color Accent=Color.FromArgb(246,173,132);
        private bool hover;
        private Bitmap glow;
        private Size glowSize;
        private string glowSymbol;
        private Color glowColor;
        public PlayerButton() {
            SetStyle(ControlStyles.UserPaint|ControlStyles.AllPaintingInWmPaint|ControlStyles.OptimizedDoubleBuffer,true);
            FlatStyle=FlatStyle.Flat; FlatAppearance.BorderSize=0; Cursor=Cursors.Hand;
            ForeColor=Color.FromArgb(240,242,247); BackColor=Color.FromArgb(23,24,29);
        }
        protected override void OnMouseEnter(EventArgs e) { hover=true; Invalidate(); base.OnMouseEnter(e); }
        protected override void OnMouseLeave(EventArgs e) { hover=false; Invalidate(); base.OnMouseLeave(e); }
        protected override void OnGotFocus(EventArgs e) { Invalidate(); base.OnGotFocus(e); }
        protected override void OnLostFocus(EventArgs e) { Invalidate(); base.OnLostFocus(e); }
        protected override void OnPaint(PaintEventArgs e) {
            var g=e.Graphics; g.Clear(BackColor); g.SmoothingMode=SmoothingMode.AntiAlias;
            g.PixelOffsetMode=PixelOffsetMode.HighQuality;
            bool active=Enabled && (hover || Focused);
            if(string.IsNullOrEmpty(Symbol)) {
                if(TextOnly) {
                    TextRenderer.DrawText(g,Text,Font,ClientRectangle,active?Color.White:ForeColor,TextFormatFlags.HorizontalCenter|TextFormatFlags.VerticalCenter);
                    if(active) using(var edge=new Pen(Color.White,Math.Max(1,2*g.DpiX/96f))) g.DrawLine(edge,Padding.Left,Height-6*g.DpiX/96f,Width-Padding.Right,Height-6*g.DpiX/96f);
                    return;
                }
                using(var path=Round(new RectangleF(2,2,Width-4,Height-4),8*Math.Min(Width,Height)/40f)) {
                    if(active) using(var brush=new SolidBrush(Color.FromArgb(43,45,53))) g.FillPath(brush,path);
                    if(Focused) using(var edge=new Pen(Accent,1.2f)) g.DrawPath(edge,path);
                }
                TextRenderer.DrawText(g,Text,Font,ClientRectangle,ForeColor,TextFormatFlags.HorizontalCenter|TextFormatFlags.VerticalCenter);
                return;
            }
            string symbol=Symbol=="pause" && Text=="▶"?"play":Symbol;
            var highlight=StrongFocus?Color.White:Color.FromArgb((Accent.R*4+255)/5,(Accent.G*4+255)/5,(Accent.B*4+255)/5);
            var normal=StrongFocus?Color.FromArgb(145,151,164):Color.FromArgb((ForeColor.R*9+BackColor.R)/10,(ForeColor.G*9+BackColor.G)/10,(ForeColor.B*9+BackColor.B)/10);
            var color=Enabled?(active?highlight:normal):Color.FromArgb(100,103,112);
            if(active) {
                if(glow==null || glowSize!=Size || glowSymbol!=symbol || glowColor!=highlight) {
                    if(glow!=null) glow.Dispose();
                    glow=CreateGlow(symbol,highlight); glowSize=Size; glowSymbol=symbol; glowColor=highlight;
                }
                g.DrawImageUnscaled(glow,0,0);
            }
            DrawGlyph(g,symbol,color);
        }
        private void DrawGlyph(Graphics graphics,string symbol,Color color) {
            float scale=Math.Min(Width,Height)*(symbol=="play" || symbol=="pause"?.70f:.64f)/24f;
            var saved=graphics.Save();
            graphics.TranslateTransform(Width/2f,Height/2f); graphics.ScaleTransform(scale,scale); graphics.TranslateTransform(-12,-12);
            PlayerGlyphs.Draw(graphics,symbol,color); graphics.Restore(saved);
        }
        private Bitmap CreateGlow(string symbol,Color color) {
            int width=Width,height=Height;
            var image=new Bitmap(width,height,PixelFormat.Format32bppArgb);
            using(var graphics=Graphics.FromImage(image)) {
                graphics.SmoothingMode=SmoothingMode.AntiAlias; graphics.PixelOffsetMode=PixelOffsetMode.HighQuality;
                DrawGlyph(graphics,symbol,Color.White);
            }
            var rectangle=new Rectangle(0,0,width,height);
            var data=image.LockBits(rectangle,ImageLockMode.ReadWrite,PixelFormat.Format32bppArgb);
            try {
                int stride=data.Stride/4;
                var pixels=new int[stride*height]; Marshal.Copy(data.Scan0,pixels,0,pixels.Length);
                double sigma=Math.Max(.8,Math.Min(width,height)/44.0*2);
                int radius=(int)Math.Ceiling(sigma*3); var kernel=new double[radius*2+1]; double sum=0;
                for(int i=-radius;i<=radius;i++) { double value=Math.Exp(-i*i/(2*sigma*sigma)); kernel[i+radius]=value; sum+=value; }
                for(int i=0;i<kernel.Length;i++) kernel[i]/=sum;
                var horizontal=new double[width*height];
                for(int y=0;y<height;y++) for(int x=0;x<width;x++) {
                    double alpha=0;
                    for(int k=-radius;k<=radius;k++) if(x+k>=0 && x+k<width) alpha+=((uint)pixels[y*stride+x+k]>>24)*kernel[k+radius];
                    horizontal[y*width+x]=alpha;
                }
                for(int y=0;y<height;y++) for(int x=0;x<width;x++) {
                    double alpha=0;
                    for(int k=-radius;k<=radius;k++) if(y+k>=0 && y+k<height) alpha+=horizontal[(y+k)*width+x]*kernel[k+radius];
                    pixels[y*stride+x]=((int)Math.Round(alpha*.28)<<24)|(color.R<<16)|(color.G<<8)|color.B;
                }
                Marshal.Copy(pixels,0,data.Scan0,pixels.Length);
            } finally { image.UnlockBits(data); }
            return image;
        }
        protected override void Dispose(bool disposing) {
            if(disposing && glow!=null) { glow.Dispose(); glow=null; }
            base.Dispose(disposing);
        }
        private static GraphicsPath Round(RectangleF r,float radius) {
            var path=new GraphicsPath(); float d=radius*2;
            path.AddArc(r.Left,r.Top,d,d,180,90); path.AddArc(r.Right-d,r.Top,d,d,270,90);
            path.AddArc(r.Right-d,r.Bottom-d,d,d,0,90); path.AddArc(r.Left,r.Bottom-d,d,d,90,90); path.CloseFigure(); return path;
        }
    }
}
