using System;
using System.Drawing;
using System.Globalization;
using System.Threading.Tasks;
using System.Windows.Forms;

namespace Pluxy {
    internal sealed class PlayerWindow : Form {
        private readonly MpvSession mpv=new MpvSession();
        private readonly LibraryClient api;
        private readonly string server,id,url,audio;
        private readonly double start;
        private readonly Panel screen=new Panel { Dock=DockStyle.Fill,BackColor=Color.Black };
        private readonly PlayerSlider timeline=new PlayerSlider { Dock=DockStyle.Top,Height=22,Maximum=10000,AccessibleName="Position dans la vidéo" };
        private readonly Label time=new Label { AutoSize=true,ForeColor=Color.FromArgb(185,196,212),Margin=new Padding(12,14,8,0) };
        private readonly Timer timer=new Timer { Interval=500 };
        private readonly Timer seekTimer=new Timer { Interval=80 };
        private readonly System.Diagnostics.Stopwatch seekClock=new System.Diagnostics.Stopwatch();
        private int seekDirection;
        private double seekBase,seekTarget;
        private readonly Label status=new Label { Dock=DockStyle.Top,AutoSize=false,Height=28,ForeColor=Color.FromArgb(242,179,154),TextAlign=ContentAlignment.MiddleCenter,BackColor=Color.FromArgb(18,24,33),Text="Chargement de la vidéo…" };
        private double position,duration;
        private bool dragging,ticking,ready,full,closing;
        private int ticks;
        private readonly PlaybackBufferPolicy bufferPolicy=new PlaybackBufferPolicy();
        private DateTime seekGraceUntil=DateTime.MinValue;
        private bool restoreReserveAfterSeek;
        private Button pause;
        private Button fullscreenButton;
        private Panel controls;
        private readonly ToolTip hints=new ToolTip();
        private Point lastPointer;
        private DateTime lastInteraction=DateTime.UtcNow;
        private Rectangle windowBounds;
        private FormWindowState windowState;
        private Task saveObserver;
        private readonly string progressSession=Guid.NewGuid().ToString("N");
        private long progressSequence;
        public PlayerWindow(LibraryClient api,string id,string title,string url,string audio,double start,object[] subtitles=null) {
            this.api=api; this.id=id; server=api.Server; this.url=url; this.audio=audio; this.start=start;
            Text=title+" · Pluxy"; Size=new Size(1180,760); MinimumSize=new Size(700,440); StartPosition=FormStartPosition.CenterParent;
            BackColor=Color.FromArgb(12,16,22); Font=new Font("Segoe UI",10); KeyPreview=true;
            string theme=Preferences.Load().theme;
            var accent=theme=="ocean"?Color.FromArgb(133,204,220):theme=="midnight"?Color.FromArgb(182,173,242):Color.FromArgb(237,184,148);
            timeline.Accent=accent; status.ForeColor=accent;
            controls=new Panel { Height=76,Padding=new Padding(18,4,18,8),BackColor=Color.FromArgb(23,24,29),Anchor=AnchorStyles.Top|AnchorStyles.Left };
            var buttons=new FlowLayoutPanel { Dock=DockStyle.Fill,WrapContents=false };
            pause=ControlButton("Ⅱ","Lire / pause · Espace",async(s,e)=> { if(!ready || closing) return; try { if(Convert.ToBoolean(await mpv.Command("get_property","eof-reached"))) { await Run("seek",0,"absolute+exact"); await Run("set_property","pause",false); } else await Run("cycle","pause"); } catch(Exception error) { Native.Log(error); } });
            buttons.Controls.Add(ControlButton("−10","Reculer de 10 secondes · Flèche gauche",async(s,e)=>await Run("seek",-10,"relative+exact")));
            buttons.Controls.Add(pause); buttons.Controls.Add(ControlButton("+10","Avancer de 10 secondes · Flèche droite",async(s,e)=>await Run("seek",10,"relative+exact")));
            buttons.Controls.Add(time);
            buttons.Controls.Add(ControlButton("Audio","Choisir la piste audio",async(s,e)=>await ChooseTracks("audio")));
            buttons.Controls.Add(ControlButton("Sous-titres","Choisir les sous-titres",async(s,e)=>await ChooseTracks("sub")));
            fullscreenButton=ControlButton("⛶","Plein écran · F11",(s,e)=>ToggleFull()); buttons.Controls.Add(fullscreenButton);
            var volume=new PlayerSlider { Maximum=100,Value=100,Width=88,Height=32,Accent=accent,BackColor=controls.BackColor,AccessibleName="Volume",Margin=new Padding(12,3,0,0) };
            volume.MouseUp+=async(s,e)=>await Run("set_property","volume",volume.Value); volume.KeyUp+=async(s,e)=>await Run("set_property","volume",volume.Value); buttons.Controls.Add(volume);
            controls.Controls.Add(buttons); controls.Controls.Add(timeline);
            Controls.Add(screen); Controls.Add(controls); Controls.Add(status);
            controls.BringToFront();
            Resize+=(s,e)=> { controls.SetBounds(0,ClientSize.Height-controls.Height,ClientSize.Width,controls.Height); };
            controls.SetBounds(0,ClientSize.Height-controls.Height,ClientSize.Width,controls.Height);
            time.Margin=new Padding(10,10,15,0); time.Font=new Font("Segoe UI",9);
            timeline.AccessibleName="Position dans la vidéo";
            timeline.MouseDown+=(s,e)=>dragging=true;
            timeline.MouseUp+=async(s,e)=> { if(ready && duration>0) await Run("seek",duration*timeline.Value/10000,"absolute+exact"); dragging=false; };
            timeline.KeyUp+=async(s,e)=> { if(ready && duration>0) await Run("seek",duration*timeline.Value/10000,"absolute+exact"); };
            screen.DoubleClick+=(s,e)=>ToggleFull();
            KeyDown+=async(s,e)=> {
                ShowControls();
                if(ActiveControl is PlayerSlider) return;
                if(e.KeyCode==Keys.Space) { e.Handled=true; await Run("cycle","pause"); }
                if(e.KeyCode==Keys.Right || e.KeyCode==Keys.Left) { e.Handled=true; StartSeek(e.KeyCode==Keys.Right?1:-1); }
                if(e.KeyCode==Keys.F || e.KeyCode==Keys.F11) { e.Handled=true; ToggleFull(); }
                if(e.KeyCode==Keys.Escape) { if(full) ToggleFull(); else Close(); }
            };
            KeyUp+=async(s,e)=> { if((e.KeyCode==Keys.Right || e.KeyCode==Keys.Left) && seekDirection!=0) { e.Handled=true; UpdateSeek(); seekDirection=0; seekTimer.Stop(); seekClock.Stop(); await Run("seek",seekTarget,"absolute+exact"); } };
            seekTimer.Tick+=(s,e)=>UpdateSeek();
            Deactivate+=(s,e)=> { seekDirection=0; seekTimer.Stop(); seekClock.Stop(); };
            Shown+=async(s,e)=> {
                Native.DarkFrame(this);
                try { await mpv.Start(url,screen.Handle,audio,start);
                    foreach(var value in subtitles ?? new object[0]) { var sub=Native.Object(value); int index=(int)Native.Number(sub,"index",-1); if(index<0) continue;
                        try { await mpv.Command("sub-add",server+"/stream/subs/"+LibraryClient.ItemPath(id)+"/"+index,"auto",Native.Text(sub,"label"),Native.Text(sub,"lang")); } catch(Exception error) { Native.Log(error); }
                    }
                    ready=true; timer.Start(); }
                catch(Exception error) { Native.Log(error); status.Text="Lecture impossible. Vérifiez que le serveur est disponible."; }
            };
            timer.Tick+=async(s,e)=>await Tick();
            FormClosing+=async(s,e)=> {
                if(closing) return; e.Cancel=true; await Shutdown();
            };
        }
        public async Task Shutdown() {
            if(closing) return; closing=true; timer.Stop(); seekTimer.Stop(); mpv.Dispose();
            try { var save=api.SaveProgress(server,id,position,duration,progressSession,++progressSequence); await Task.WhenAny(save,Task.Delay(2000)); if(save.IsCompleted) await save; else saveObserver=save.ContinueWith(t=>Native.Log(t.Exception),TaskContinuationOptions.OnlyOnFaulted); } catch(Exception error) { Native.Log(error); }
            timer.Dispose(); seekTimer.Dispose(); hints.Dispose(); Close();
        }
        protected override bool ProcessCmdKey(ref Message message,Keys keyData) {
            if(!(ActiveControl is PlayerSlider) && (keyData==Keys.Right || keyData==Keys.Left)) {
                StartSeek(keyData==Keys.Right?1:-1); return true;
            }
            return base.ProcessCmdKey(ref message,keyData);
        }
        private async Task Run(params object[] command) {
            if(!ready || closing) return;
            try {
                if(command.Length>0 && Convert.ToString(command[0])=="seek") {
                    seekGraceUntil=DateTime.UtcNow.AddSeconds(5); restoreReserveAfterSeek=true;
                    await mpv.Command("set_property","cache-pause-wait",3);
                }
                await mpv.Command(command);
            } catch(Exception error) { Native.Log(error); status.Visible=true; status.Text="La lecture a été interrompue. Fermez le lecteur et réessayez."; }
        }
        private void StartSeek(int direction) { if(!ready || closing || duration<=0 || seekDirection!=0) return; ShowControls(); seekDirection=direction; seekBase=position; seekClock.Restart(); UpdateSeek(); seekTimer.Start(); }
        private void UpdateSeek() { if(seekDirection==0) return; double delta=SeekAcceleration.Distance(seekClock.ElapsedMilliseconds)*seekDirection; seekTarget=Math.Max(0,Math.Min(duration-.25,seekBase+delta)); status.Visible=true; status.Text=(delta>0?"+":"−")+Clock(Math.Abs(delta))+" · "+SeekAcceleration.Speed(seekClock.ElapsedMilliseconds)+" · "+Clock(seekTarget); }
        private string Clock(double value) { var t=TimeSpan.FromSeconds(Math.Max(0,value)); return (t.TotalHours>=1?t.Hours.ToString("00")+":":"")+t.Minutes.ToString("00")+":"+t.Seconds.ToString("00"); }
        private async Task Tick() {
            if(ticking || closing) return; ticking=true;
            try {
                var pos=await mpv.Command("get_property","time-pos"); var dur=await mpv.Command("get_property","duration"); var paused=await mpv.Command("get_property","pause");
                bool buffering=Convert.ToBoolean(await mpv.Command("get_property","paused-for-cache"));
                if(bufferPolicy.Observe(buffering,Convert.ToBoolean(paused),DateTime.UtcNow<seekGraceUntil || ticks==0))
                    await mpv.Command("set_property","cache-pause-wait",bufferPolicy.ResumeSeconds);
                if(restoreReserveAfterSeek && !buffering && DateTime.UtcNow>=seekGraceUntil) {
                    await mpv.Command("set_property","cache-pause-wait",bufferPolicy.ResumeSeconds);
                    restoreReserveAfterSeek=false;
                }
                position=Convert.ToDouble(pos,CultureInfo.InvariantCulture); duration=Convert.ToDouble(dur,CultureInfo.InvariantCulture);
                if(!dragging && duration>0) timeline.Value=(int)Math.Max(0,Math.Min(10000,position/duration*10000));
                time.Text=Clock(position)+" / "+Clock(duration); pause.Text=Convert.ToBoolean(paused)?"▶":"Ⅱ";
                var pointer=Cursor.Position;
                if(pointer!=lastPointer && Bounds.Contains(pointer)) ShowControls();
                lastPointer=pointer;
                if(ready && !Convert.ToBoolean(paused) && !buffering && !dragging && seekDirection==0 && !controls.RectangleToScreen(controls.ClientRectangle).Contains(pointer) && DateTime.UtcNow-lastInteraction>TimeSpan.FromSeconds(3)) controls.Visible=false;
                if(seekDirection==0) { status.Visible=buffering; if(buffering) status.Text="Chargement en avance…"; }
                if(++ticks%20==0) { try { await api.SaveProgress(server,id,position,duration,progressSession,++progressSequence); } catch(Exception error) { Native.Log(error); } }
            } catch(Exception error) { if(!closing) { Native.Log(error); status.Visible=true; status.Text="Chargement…"; } }
            finally { ticking=false; }
        }
        private async Task ChooseTracks(string type) {
            if(!ready) return;
            try {
                var tracks=await mpv.Command("get_property","track-list") as object[];
                var menu=new ContextMenuStrip { BackColor=Color.FromArgb(25,33,44),ForeColor=Color.White };
                if(type=="sub") menu.Items.Add("Désactivés",null,async(s,e)=>await Run("set_property","sid","no"));
                foreach(var value in tracks ?? new object[0]) { var track=Native.Object(value); if(Native.Text(track,"type")!=type) continue;
                    var trackId=(int)Native.Number(track,"id"); var label=Native.Text(track,"title",Native.Text(track,"lang","Piste "+trackId))+" · "+Native.Text(track,"codec");
                    menu.Items.Add(label,null,async(s,e)=>await Run("set_property",type=="sub"?"sid":"aid",trackId));
                }
                menu.Show(Cursor.Position);
            } catch(Exception error) { Native.Log(error); }
        }
        private Button ControlButton(string text,string help,EventHandler action) {
            var button=Native.Button(text,action); button.AutoSize=true; button.Height=32; button.MinimumSize=new Size(36,32);
            button.Font=new Font("Segoe UI",9); button.Margin=new Padding(3,1,3,0); button.Padding=new Padding(7,2,7,2);
            button.BackColor=Color.FromArgb(23,24,29); button.FlatAppearance.BorderSize=0;
            button.FlatAppearance.MouseOverBackColor=Color.FromArgb(43,44,51); button.AccessibleName=help; hints.SetToolTip(button,help); return button;
        }
        private void ShowControls() { lastInteraction=DateTime.UtcNow; controls.Visible=true; controls.BringToFront(); }
        private void ToggleFull() {
            if(!full) { windowBounds=WindowState==FormWindowState.Normal?Bounds:RestoreBounds; windowState=WindowState; full=true; WindowState=FormWindowState.Normal; FormBorderStyle=FormBorderStyle.None; Bounds=Screen.FromControl(this).Bounds; }
            else { full=false; WindowState=FormWindowState.Normal; FormBorderStyle=FormBorderStyle.Sizable; Bounds=windowBounds; WindowState=windowState; }
            fullscreenButton.AccessibleName=full?"Quitter le plein écran · Échap":"Plein écran · F11";
            hints.SetToolTip(fullscreenButton,fullscreenButton.AccessibleName);
            ShowControls();
        }
        protected override void WndProc(ref Message message) {
            if(message.Msg==0x112 && (message.WParam.ToInt64()&0xFFF0)==0xF030) { if(!full) ToggleFull(); return; }
            base.WndProc(ref message);
        }
    }

    internal sealed class PlayerSlider : Control {
        private int value;
        private bool held;
        public int Maximum { get; set; }
        public Color Accent { get; set; }
        public int Value { get { return value; } set { this.value=Math.Max(0,Math.Min(Maximum,value)); Invalidate(); } }
        public PlayerSlider() { Maximum=100; Height=22; TabStop=true; AccessibleRole=AccessibleRole.Slider; Accent=Color.FromArgb(237,184,148); BackColor=Color.FromArgb(23,24,29); Cursor=Cursors.Hand; SetStyle(ControlStyles.AllPaintingInWmPaint|ControlStyles.OptimizedDoubleBuffer|ControlStyles.UserPaint|ControlStyles.Selectable,true); }
        protected override void OnPaint(PaintEventArgs e) {
            base.OnPaint(e); e.Graphics.SmoothingMode=System.Drawing.Drawing2D.SmoothingMode.AntiAlias;
            float x=7+(Width-14)*(Maximum>0?(float)Value/Maximum:0), y=Height/2f;
            using(var track=new Pen(Color.FromArgb(68,69,78),3)) using(var fill=new Pen(Accent,3)) using(var thumb=new SolidBrush(Color.FromArgb(245,238,231))) {
                track.StartCap=track.EndCap=System.Drawing.Drawing2D.LineCap.Round; fill.StartCap=fill.EndCap=System.Drawing.Drawing2D.LineCap.Round;
                e.Graphics.DrawLine(track,7,y,Width-7,y); e.Graphics.DrawLine(fill,7,y,x,y);
                if(held || Focused || ClientRectangle.Contains(PointToClient(MousePosition))) e.Graphics.FillEllipse(thumb,x-5,y-5,10,10);
            }
        }
        private void SetPosition(int x) { Value=(int)(Math.Max(0,Math.Min(1,(x-7)/(double)Math.Max(1,Width-14)))*Maximum); }
        protected override void OnMouseDown(MouseEventArgs e) { if(e.Button==MouseButtons.Left) { held=true; Capture=true; Focus(); SetPosition(e.X); } base.OnMouseDown(e); }
        protected override void OnMouseMove(MouseEventArgs e) { if(held) SetPosition(e.X); Invalidate(); base.OnMouseMove(e); }
        protected override void OnMouseUp(MouseEventArgs e) { if(held) SetPosition(e.X); held=false; Capture=false; base.OnMouseUp(e); Invalidate(); }
        protected override void OnMouseLeave(EventArgs e) { base.OnMouseLeave(e); Invalidate(); }
        protected override bool IsInputKey(Keys keyData) { return keyData==Keys.Left || keyData==Keys.Right || base.IsInputKey(keyData); }
        protected override void OnKeyDown(KeyEventArgs e) { if(e.KeyCode==Keys.Left || e.KeyCode==Keys.Right) { Value+=(e.KeyCode==Keys.Right?1:-1)*Math.Max(1,Maximum/100); e.Handled=true; } base.OnKeyDown(e); }
    }
}
