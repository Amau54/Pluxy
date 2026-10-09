using System;
using System.Drawing;
using System.Globalization;
using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
using System.Windows.Forms;

namespace Pluxy {
    internal sealed class PlayerWindow : Form {
        private MpvSession mpv=new MpvSession();
        private readonly LibraryClient api;
        private string server,id,url;
        private readonly string audio;
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
        private object[] subtitleTracks;
        private readonly List<object> episodes;
        private bool recovering,wasPaused,autoplay;
        private int recoveryAttempts;
        private DateTime stalledSince=DateTime.MinValue,healthySince=DateTime.MinValue;
        private Button retryButton,nextButton;
        public PlayerWindow(LibraryClient api,string id,string title,string url,string audio,double start,object[] subtitles=null)
            : this(api,id,title,url,audio,start,subtitles,null) { }
        public PlayerWindow(LibraryClient api,string id,string title,string url,string audio,double start,object[] subtitles,object[] queue) {
            this.api=api; this.id=id; server=api.Server; this.url=url; this.audio=audio; this.start=start;
            subtitleTracks=subtitles; position=start; autoplay=Preferences.Load().autoplay;
            episodes=(queue ?? new object[0]).OrderBy(e=>Native.Number(Native.Object(e),"season")).ThenBy(e=>Native.Number(Native.Object(e),"episode")).ThenBy(e=>Native.Text(Native.Object(e),"id")).ToList();
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
            nextButton=ControlButton("▷|","Épisode suivant",async(s,e)=>await NextEpisode()); nextButton.Visible=episodes.Count>1; buttons.Controls.Add(nextButton);
            var episodeMenu=new ContextMenuStrip(); var automatic=new ToolStripMenuItem("Enchaîner les épisodes") { Checked=autoplay,CheckOnClick=true };
            automatic.CheckedChanged+=(s,e)=> { autoplay=automatic.Checked; var settings=Preferences.Load(); settings.autoplay=autoplay; settings.Save(); }; episodeMenu.Items.Add(automatic); nextButton.ContextMenuStrip=episodeMenu;
            buttons.Controls.Add(time);
            buttons.Controls.Add(ControlButton("Audio","Choisir la piste audio",async(s,e)=>await ChooseTracks("audio")));
            buttons.Controls.Add(ControlButton("Sous-titres","Choisir les sous-titres",async(s,e)=>await ChooseTracks("sub")));
            fullscreenButton=ControlButton("⛶","Plein écran · F11",(s,e)=>ToggleFull()); buttons.Controls.Add(fullscreenButton);
            var volume=new PlayerSlider { Maximum=100,Value=100,Width=88,Height=32,Accent=accent,BackColor=controls.BackColor,AccessibleName="Volume",Margin=new Padding(12,3,0,0) };
            volume.MouseUp+=async(s,e)=>await Run("set_property","volume",volume.Value); volume.KeyUp+=async(s,e)=>await Run("set_property","volume",volume.Value); buttons.Controls.Add(volume);
            controls.Controls.Add(buttons); controls.Controls.Add(timeline);
            Controls.Add(screen); Controls.Add(controls); Controls.Add(status);
            retryButton=ControlButton("Réessayer","Reprendre au même endroit",async(s,e)=> { recoveryAttempts=0; await Recover(position); }); retryButton.Visible=false; retryButton.Dock=DockStyle.Top; Controls.Add(retryButton); retryButton.BringToFront();
            controls.BringToFront();
            Resize+=(s,e)=> { controls.SetBounds(0,ClientSize.Height-controls.Height,ClientSize.Width,controls.Height); };
            controls.SetBounds(0,ClientSize.Height-controls.Height,ClientSize.Width,controls.Height);
            time.Margin=new Padding(10,10,15,0); time.Font=new Font("Segoe UI",9);
            timeline.AccessibleName="Position dans la vidéo";
            timeline.MouseDown+=(s,e)=>dragging=true;
            timeline.MouseUp+=async(s,e)=> { if(ready && duration>0) await Run("seek",duration*timeline.Value/10000,"absolute+exact"); dragging=false; };
            timeline.KeyDown+=(s,e)=> { if(e.KeyCode==Keys.Left || e.KeyCode==Keys.Right) dragging=true; };
            timeline.KeyUp+=async(s,e)=> { if(e.KeyCode!=Keys.Left && e.KeyCode!=Keys.Right) return; try { if(ready && duration>0) await Run("seek",duration*timeline.Value/10000,"absolute+exact"); } finally { dragging=false; } };
            timeline.LostFocus+=(s,e)=>dragging=false;
            screen.DoubleClick+=(s,e)=>ToggleFull();
            KeyDown+=async(s,e)=> {
                ShowControls();
                if(e.KeyCode==Keys.Escape) { e.Handled=true; e.SuppressKeyPress=true; if(full) ToggleFull(); else Close(); return; }
                if(e.KeyCode==Keys.F || e.KeyCode==Keys.F11) { e.Handled=true; e.SuppressKeyPress=true; ToggleFull(); return; }
                if(ActiveControl is PlayerSlider) return;
                if(e.KeyCode==Keys.Space) { e.Handled=true; await Run("cycle","pause"); }
                if(e.KeyCode==Keys.Right || e.KeyCode==Keys.Left) { e.Handled=true; StartSeek(e.KeyCode==Keys.Right?1:-1); }
            };
            KeyUp+=async(s,e)=> { if((e.KeyCode==Keys.Right || e.KeyCode==Keys.Left) && seekDirection!=0) { e.Handled=true; UpdateSeek(); seekDirection=0; seekTimer.Stop(); seekClock.Stop(); await Run("seek",seekTarget,"absolute+exact"); } };
            seekTimer.Tick+=(s,e)=>UpdateSeek();
            Deactivate+=(s,e)=> { seekDirection=0; seekTimer.Stop(); seekClock.Stop(); };
            Shown+=async(s,e)=> {
                Native.DarkFrame(this);
                bool opened=false;
                try { await OpenMedia(start); opened=true; }
                catch(Exception error) { Native.Log(error); }
                if(!opened) await Recover(start);
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
            if(keyData==Keys.F11 || keyData==Keys.F) { ToggleFull(); return true; }
            if(keyData==Keys.Escape) { if(full) ToggleFull(); else Close(); return true; }
            if(!(ActiveControl is PlayerSlider) && (keyData==Keys.Right || keyData==Keys.Left)) {
                StartSeek(keyData==Keys.Right?1:-1); return true;
            }
            return base.ProcessCmdKey(ref message,keyData);
        }
        private async Task Run(params object[] command) {
            if(!ready || closing) return;
            double resume=position; bool failed=false;
            try {
                if(command.Length>0 && Convert.ToString(command[0])=="seek") {
                    double value=Convert.ToDouble(command[1],CultureInfo.InvariantCulture);
                    resume=Convert.ToString(command[2]).StartsWith("absolute",StringComparison.Ordinal)?value:position+value;
                    resume=Math.Max(0,duration>0?Math.Min(duration-.25,resume):resume);
                    seekGraceUntil=DateTime.UtcNow.AddSeconds(5); restoreReserveAfterSeek=true;
                    await mpv.Command("set_property","cache-pause-wait",3);
                }
                await mpv.Command(command);
            } catch(Exception error) { Native.Log(error); failed=true; }
            if(failed) await Recover(resume);
        }
        private async Task OpenMedia(double resume) {
            ready=false; timer.Stop();
            await mpv.Start(url,screen.Handle,audio,resume);
            if(closing) return;
            foreach(var value in subtitleTracks ?? new object[0]) { var sub=Native.Object(value); int index=(int)Native.Number(sub,"index",-1); if(index<0) continue;
                try { await mpv.Command("sub-add",server+"/stream/subs/"+LibraryClient.ItemPath(id)+"/"+index,"auto",Native.Text(sub,"label"),Native.Text(sub,"lang")); } catch(Exception error) { Native.Log(error); }
            }
            if(wasPaused) await mpv.Command("set_property","pause",true);
            position=resume; ready=true; retryButton.Visible=false; stalledSince=DateTime.MinValue; healthySince=DateTime.UtcNow; timer.Start();
        }
        private async Task Recover(double resume) {
            if(recovering || closing) return;
            recovering=true; ready=false; timer.Stop(); seekTimer.Stop(); seekDirection=0; dragging=false; position=resume;
            try {
                while(recoveryAttempts<3 && !closing) {
                    status.Visible=true; status.Text="Reconnexion · reprise à "+Clock(resume)+"…"; ShowControls();
                    recoveryAttempts++; mpv.Dispose(); mpv=new MpvSession();
                    try {
                        server=await api.PlaybackServer(server,id); if(closing) return;
                        url=server+"/stream/direct/"+LibraryClient.ItemPath(id);
                        await OpenMedia(resume); return;
                    } catch(Exception error) { Native.Log(error); status.Text=error.Message; }
                    if(recoveryAttempts<3) await Task.Delay(1500);
                }
                mpv.Dispose(); status.Visible=true; status.Text="Lecture interrompue à "+Clock(resume)+". Réessayez lorsque le serveur est disponible."; retryButton.Visible=true;
            } finally { recovering=false; }
        }
        private async Task NextEpisode() {
            if(recovering || closing || episodes.Count<2) return;
            int index=episodes.FindIndex(e=>Native.Text(Native.Object(e),"id")==id); if(index<0 || index+1>=episodes.Count) return;
            recovering=true; ready=false; timer.Stop(); bool failed=false;
            try {
                await api.SaveProgress(server,id,position,duration,progressSession,++progressSequence);
                var item=Native.Object(episodes[index+1]); id=Native.Text(item,"id"); position=duration=0; ticks=0; recoveryAttempts=0; wasPaused=false;
                Text=Native.Text(item,"title")+" · Pluxy"; url=server+"/stream/direct/"+LibraryClient.ItemPath(id);
                subtitleTracks=item.ContainsKey("subtitles")?item["subtitles"] as object[]:null;
                mpv.Dispose(); mpv=new MpvSession(); await OpenMedia(0);
            } catch(Exception error) { Native.Log(error); failed=true; }
            finally { recovering=false; }
            if(failed) await Recover(position);
        }
        private void StartSeek(int direction) { if(!ready || closing || duration<=0 || seekDirection!=0) return; ShowControls(); seekDirection=direction; seekBase=position; seekClock.Restart(); UpdateSeek(); seekTimer.Start(); }
        private void UpdateSeek() { if(seekDirection==0) return; double delta=SeekAcceleration.Distance(seekClock.ElapsedMilliseconds)*seekDirection; seekTarget=Math.Max(0,Math.Min(duration-.25,seekBase+delta)); status.Visible=true; status.Text=(delta>0?"+":"−")+Clock(Math.Abs(delta))+" · "+SeekAcceleration.Speed(seekClock.ElapsedMilliseconds)+" · "+Clock(seekTarget); }
        private string Clock(double value) { var t=TimeSpan.FromSeconds(Math.Max(0,value)); return (t.TotalHours>=1?t.Hours.ToString("00")+":":"")+t.Minutes.ToString("00")+":"+t.Seconds.ToString("00"); }
        private async Task Tick() {
            if(ticking || closing || !ready || recovering) return; ticking=true; bool failed=false;
            try {
                var pos=await mpv.Command("get_property","time-pos"); var dur=await mpv.Command("get_property","duration"); var paused=await mpv.Command("get_property","pause");
                bool buffering=Convert.ToBoolean(await mpv.Command("get_property","paused-for-cache"));
                wasPaused=Convert.ToBoolean(paused);
                if(buffering && !wasPaused) { if(stalledSince==DateTime.MinValue) stalledSince=DateTime.UtcNow; if(DateTime.UtcNow-stalledSince>TimeSpan.FromSeconds(45)) { await Recover(position); return; } }
                else { stalledSince=DateTime.MinValue; if(DateTime.UtcNow-healthySince>TimeSpan.FromMinutes(1)) recoveryAttempts=0; }
                if(bufferPolicy.Observe(buffering,Convert.ToBoolean(paused),DateTime.UtcNow<seekGraceUntil || ticks==0))
                    await mpv.Command("set_property","cache-pause-wait",bufferPolicy.ResumeSeconds);
                if(restoreReserveAfterSeek && !buffering && DateTime.UtcNow>=seekGraceUntil) {
                    await mpv.Command("set_property","cache-pause-wait",bufferPolicy.ResumeSeconds);
                    restoreReserveAfterSeek=false;
                }
                position=Convert.ToDouble(pos,CultureInfo.InvariantCulture); duration=Convert.ToDouble(dur,CultureInfo.InvariantCulture);
                int episodeIndex=episodes.FindIndex(e=>Native.Text(Native.Object(e),"id")==id);
                nextButton.Enabled=episodeIndex>=0 && episodeIndex+1<episodes.Count;
                if(autoplay && nextButton.Enabled && Convert.ToBoolean(await mpv.Command("get_property","eof-reached"))) { await NextEpisode(); return; }
                if(!dragging && duration>0) timeline.Value=(int)Math.Max(0,Math.Min(10000,position/duration*10000));
                time.Text=Clock(position)+" / "+Clock(duration); pause.Text=Convert.ToBoolean(paused)?"▶":"Ⅱ";
                var pointer=Cursor.Position;
                if(pointer!=lastPointer && Bounds.Contains(pointer)) ShowControls();
                lastPointer=pointer;
                if(ready && !Convert.ToBoolean(paused) && !buffering && !dragging && seekDirection==0 && !controls.RectangleToScreen(controls.ClientRectangle).Contains(pointer) && DateTime.UtcNow-lastInteraction>TimeSpan.FromSeconds(3)) controls.Visible=false;
                if(seekDirection==0) { status.Visible=buffering; if(buffering) status.Text="Chargement en avance…"; }
                if(++ticks%20==0) { try { await api.SaveProgress(server,id,position,duration,progressSession,++progressSequence); } catch(Exception error) { Native.Log(error); } }
            } catch(Exception error) { if(!closing) { Native.Log(error); failed=true; } }
            finally { ticking=false; }
            if(failed) await Recover(position);
        }
        private async Task ChooseTracks(string type) {
            if(!ready) return;
            try {
                var tracks=await mpv.Command("get_property","track-list") as object[];
                var menu=new ContextMenuStrip { BackColor=Color.FromArgb(25,33,44),ForeColor=Color.White };
                string selected=Convert.ToString(await mpv.Command("get_property",type=="sub"?"sid":"aid"),CultureInfo.InvariantCulture);
                if(type=="sub") { var off=new ToolStripMenuItem("Désactivés") { Checked=selected=="no" || selected=="False" }; off.Click+=async(s,e)=>await Run("set_property","sid","no"); menu.Items.Add(off); }
                foreach(var value in tracks ?? new object[0]) { var track=Native.Object(value); if(Native.Text(track,"type")!=type) continue;
                    var trackId=(int)Native.Number(track,"id"); var label=Native.Text(track,"title",Native.Text(track,"lang","Piste "+trackId))+" · "+Native.Text(track,"codec");
                    var entry=new ToolStripMenuItem(label) { Checked=selected==trackId.ToString(CultureInfo.InvariantCulture) }; entry.Click+=async(s,e)=>await Run("set_property",type=="sub"?"sid":"aid",trackId); menu.Items.Add(entry);
                }
                if(type=="audio") {
                    menu.Items.Add(new ToolStripSeparator()); string device=Convert.ToString(await mpv.Command("get_property","audio-device"));
                    foreach(var value in await mpv.Command("get_property","audio-device-list") as object[] ?? new object[0]) { var output=Native.Object(value); string name=Native.Text(output,"name"); var entry=new ToolStripMenuItem(Native.Text(output,"description",name)) { Checked=name==device }; entry.Click+=async(s,e)=>await Run("set_property","audio-device",name); menu.Items.Add(entry); }
                }
                if(menu.Items.Count==0) menu.Items.Add(new ToolStripMenuItem("Aucune piste disponible") { Enabled=false });
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
