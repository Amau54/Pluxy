using System;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Windows.Forms;

namespace Pluxy {
    internal sealed class PlayerSurface : Panel {
        public Color Accent=Color.FromArgb(246,173,132);
        public PlayerSurface() {
            BackColor=Color.FromArgb(23,24,29);
            SetStyle(ControlStyles.UserPaint|ControlStyles.AllPaintingInWmPaint|ControlStyles.OptimizedDoubleBuffer|ControlStyles.ResizeRedraw,true);
        }
        internal static GraphicsPath Round(RectangleF bounds,float radius) {
            var path=new GraphicsPath(); float diameter=Math.Min(radius*2,Math.Min(bounds.Width,bounds.Height));
            if(diameter<=0) return path;
            path.AddArc(bounds.Left,bounds.Top,diameter,diameter,180,90);
            path.AddArc(bounds.Right-diameter,bounds.Top,diameter,diameter,270,90);
            path.AddArc(bounds.Right-diameter,bounds.Bottom-diameter,diameter,diameter,0,90);
            path.AddArc(bounds.Left,bounds.Bottom-diameter,diameter,diameter,90,90); path.CloseFigure(); return path;
        }
        protected override void OnResize(EventArgs e) {
            base.OnResize(e); if(Width<2 || Height<2) return;
            using(var path=Round(new RectangleF(0,0,Width,Height),12*DeviceScale())) {
                var previous=Region; Region=new Region(path); if(previous!=null) previous.Dispose();
            }
        }
        private float DeviceScale() { using(var graphics=CreateGraphics()) return graphics.DpiX/96f; }
        protected override void OnPaint(PaintEventArgs e) {
            base.OnPaint(e); if(Width<2 || Height<2) return;
            e.Graphics.SmoothingMode=SmoothingMode.AntiAlias;
            using(var path=Round(new RectangleF(.5f,.5f,Width-1,Height-1),12*e.Graphics.DpiX/96f))
            using(var fill=new LinearGradientBrush(ClientRectangle,Color.FromArgb(33,35,43),BackColor,90f))
            using(var edge=new Pen(Color.FromArgb(50,Accent),1f)) {
                e.Graphics.FillPath(fill,path); e.Graphics.DrawPath(edge,path);
            }
        }
    }

    internal sealed class PlayerMenu : ContextMenuStrip {
        public PlayerMenu(Color accent,bool disposeOnClose=false) {
            BackColor=Color.FromArgb(25,27,34); ForeColor=Color.FromArgb(242,244,248);
            Font=new Font("Segoe UI",10); Padding=new Padding(6);
            ShowImageMargin=false; ShowCheckMargin=true; Renderer=new MenuRenderer(accent);
            ItemAdded+=(s,e)=> { e.Item.Padding=new Padding(8,6,8,6); e.Item.ForeColor=ForeColor; };
            if(disposeOnClose) Closed+=(s,e)=>Dispose();
        }
        protected override void OnOpened(EventArgs e) {
            base.OnOpened(e);
            using(var path=PlayerSurface.Round(new RectangleF(0,0,Width,Height),10)) {
                var previous=Region; Region=new Region(path); if(previous!=null) previous.Dispose();
            }
        }
        private sealed class MenuColors : ProfessionalColorTable {
            public override Color ToolStripDropDownBackground { get { return Color.FromArgb(25,27,34); } }
            public override Color ImageMarginGradientBegin { get { return ToolStripDropDownBackground; } }
            public override Color ImageMarginGradientMiddle { get { return ToolStripDropDownBackground; } }
            public override Color ImageMarginGradientEnd { get { return ToolStripDropDownBackground; } }
            public override Color MenuBorder { get { return Color.FromArgb(60,63,74); } }
            public override Color SeparatorDark { get { return Color.FromArgb(55,58,68); } }
            public override Color SeparatorLight { get { return ToolStripDropDownBackground; } }
        }
        private sealed class MenuRenderer : ToolStripProfessionalRenderer {
            private readonly Color accent;
            public MenuRenderer(Color accent) : base(new MenuColors()) { this.accent=accent; RoundedEdges=false; }
            protected override void OnRenderMenuItemBackground(ToolStripItemRenderEventArgs e) {
                if(!e.Item.Selected || !e.Item.Enabled) return;
                e.Graphics.SmoothingMode=SmoothingMode.AntiAlias;
                using(var path=PlayerSurface.Round(new RectangleF(2,1,e.Item.Width-4,e.Item.Height-2),6))
                using(var fill=new SolidBrush(Color.FromArgb(44,47,57)))
                using(var edge=new Pen(Color.FromArgb(110,accent),1f)) {
                    e.Graphics.FillPath(fill,path); e.Graphics.DrawPath(edge,path);
                }
            }
            protected override void OnRenderItemCheck(ToolStripItemImageRenderEventArgs e) {
                e.Graphics.SmoothingMode=SmoothingMode.AntiAlias;
                var r=e.ImageRectangle;
                using(var pen=new Pen(accent,2f)) {
                    pen.StartCap=pen.EndCap=LineCap.Round;
                    e.Graphics.DrawLines(pen,new[]{new PointF(r.Left+r.Width*.15f,r.Top+r.Height*.5f),new PointF(r.Left+r.Width*.4f,r.Top+r.Height*.75f),new PointF(r.Left+r.Width*.85f,r.Top+r.Height*.2f)});
                }
            }
            protected override void OnRenderArrow(ToolStripArrowRenderEventArgs e) { e.ArrowColor=Color.FromArgb(242,244,248); base.OnRenderArrow(e); }
        }
    }
}
