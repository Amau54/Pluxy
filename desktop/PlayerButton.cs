using System;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Windows.Forms;

namespace Pluxy {
    internal sealed class PlayerButton : Button {
        public string Symbol;
        private bool hover;
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
            using(var path=Round(new RectangleF(2,2,Width-4,Height-4),8)) {
                if(hover || Focused) using(var brush=new SolidBrush(Color.FromArgb(43,45,53))) g.FillPath(brush,path);
                if(Focused) using(var edge=new Pen(Color.FromArgb(224,228,237),1.2f)) g.DrawPath(edge,path);
            }
            if(string.IsNullOrEmpty(Symbol)) {
                TextRenderer.DrawText(g,Text,Font,ClientRectangle,ForeColor,TextFormatFlags.HorizontalCenter|TextFormatFlags.VerticalCenter);
                return;
            }
            var saved=g.Save(); g.TranslateTransform(Width/2f-12,Height/2f-12);
            using(var pen=new Pen(Enabled?ForeColor:Color.FromArgb(100,103,112),1.6f)) {
                pen.StartCap=pen.EndCap=LineCap.Round; pen.LineJoin=LineJoin.Round;
                string symbol=Symbol=="pause" && Text=="▶"?"play":Symbol;
                if(symbol=="play") g.DrawPolygon(pen,new[]{new PointF(8,5),new PointF(19,12),new PointF(8,19)});
                else if(symbol=="pause") { g.DrawLine(pen,8,5,8,19); g.DrawLine(pen,16,5,16,19); }
                else if(symbol=="back" || symbol=="forward") {
                    g.DrawArc(pen,4,4,16,16,symbol=="back"?210:330,285);
                    if(symbol=="back") g.DrawLines(pen,new[]{new PointF(4,3),new PointF(4,8),new PointF(9,8)});
                    else g.DrawLines(pen,new[]{new PointF(20,3),new PointF(20,8),new PointF(15,8)});
                    using(var font=new Font("Segoe UI",6.5f,FontStyle.Regular)) using(var ink=new SolidBrush(Enabled?ForeColor:Color.Gray))
                    using(var format=new StringFormat { Alignment=StringAlignment.Center,LineAlignment=StringAlignment.Center })
                        g.DrawString("10",font,ink,new RectangleF(4,5,17,15),format);
                } else if(symbol=="next") {
                    g.DrawPolygon(pen,new[]{new PointF(5,5),new PointF(15,12),new PointF(5,19)}); g.DrawLine(pen,19,5,19,19);
                } else if(symbol=="audio") {
                    g.DrawLines(pen,new[]{new PointF(4,9),new PointF(8,9),new PointF(12,5),new PointF(12,19),new PointF(8,15),new PointF(4,15),new PointF(4,9)});
                    g.DrawArc(pen,8,5,12,14,-55,110);
                } else if(symbol=="subtitles") {
                    g.DrawRectangle(pen,3,5,18,14); g.DrawLine(pen,6,11,10,11); g.DrawLine(pen,14,11,18,11);
                    g.DrawLine(pen,6,15,14,15); g.DrawLine(pen,17,15,18,15);
                } else if(symbol=="fullscreen") {
                    foreach(var pair in new[]{new[]{3,3,9,3,3,9},new[]{21,3,15,3,21,9},new[]{3,21,9,21,3,15},new[]{21,21,15,21,21,15}}) {
                        g.DrawLine(pen,pair[0],pair[1],pair[2],pair[3]); g.DrawLine(pen,pair[0],pair[1],pair[4],pair[5]);
                    }
                }
            }
            g.Restore(saved);
        }
        private static GraphicsPath Round(RectangleF r,float radius) {
            var path=new GraphicsPath(); float d=radius*2;
            path.AddArc(r.Left,r.Top,d,d,180,90); path.AddArc(r.Right-d,r.Top,d,d,270,90);
            path.AddArc(r.Right-d,r.Bottom-d,d,d,0,90); path.AddArc(r.Left,r.Bottom-d,d,d,90,90); path.CloseFigure(); return path;
        }
    }
}
