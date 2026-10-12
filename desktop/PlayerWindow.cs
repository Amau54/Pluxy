using System;
using System.Drawing;
using System.Globalization;
using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
using System.Windows.Forms;

namespace Pluxy {
    internal sealed class PlayerWindow : Form {
        private const int NextEpisodeDelaySeconds=5;
        private MpvSession mpv=new MpvSession();
        private IDictionary<string,object> media;
        private System.Threading.CancellationTokenSource skipLifetime=new System.Threading.CancellationTokenSource();
        private Task skipAnalysis;
        private object[] skipChapters=new object[0];
        private SkipSegment activeSkip;
        private Button skipButton,optionsButton;
        private readonly LibraryClient api;
        private string server,id,url;
        private object[] conversionProfiles=new object[0];
        private string conversionQuality="direct";
        private int conversionIndex;
        private double streamOffset;
        private bool conversionBusy;
        public string MediaId { get { return id; } }
        public readonly HashSet<string> ProgressIds = new HashSet<string>();
        private readonly string audio;
        private readonly string profile;
        private readonly double start;
        private readonly float uiScale;
        private readonly Color accent;
        private FlowLayoutPanel actionButtons;
        private PlayerSlider volume;
        private Button audioButton,subtitlesButton;
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
        private readonly bool hintsEnabled;
        private readonly Label actionHint=new Label { Dock=DockStyle.Bottom,TextAlign=ContentAlignment.MiddleCenter,ForeColor=Color.FromArgb(170,177,190),AutoEllipsis=true };
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
        private readonly Timer previewTimer=new Timer { Interval=400 };
        private readonly PlayerSurface previewPanel=new PlayerSurface { Size=new Size(176,122),Visible=false };
        private readonly PictureBox previewImage=new PictureBox { Dock=DockStyle.Top,Height=94,SizeMode=PictureBoxSizeMode.Zoom };
        private readonly Label previewTime=new Label { Dock=DockStyle.Bottom,Height=28,TextAlign=ContentAlignment.MiddleCenter,ForeColor=Color.FromArgb(240,242,247) };
        private readonly Dictionary<string,Bitmap> previewCache=new Dictionary<string,Bitmap>();
        private readonly System.Threading.CancellationTokenSource previewLifetime=new System.Threading.CancellationTokenSource();
        private bool previewBusy;
        private string previewUnsupported;
        private DateTime previewRetryAt=DateTime.MinValue;
        private readonly NextEpisodePrompt nextPrompt=new NextEpisodePrompt { Size=new Size(350,128),Padding=new Padding(8) };
        private readonly Label nextTitle=new Label { Dock=DockStyle.Top,Height=50,AutoEllipsis=true,ForeColor=Color.FromArgb(241,243,248),BackColor=Color.Black };
        private readonly Label nextCountdown=new Label { Dock=DockStyle.Top,Height=24,ForeColor=Color.FromArgb(173,180,195),BackColor=Color.Black };
        private Button nextPlay;
        private readonly System.Diagnostics.Stopwatch nextClock=new System.Diagnostics.Stopwatch();
        private bool nextHandled;
        public PlayerWindow(LibraryClient api,string id,string title,string url,string audio,double start,object[] subtitles=null)
            : this(api,id,title,url,audio,start,subtitles,null) { }
        public PlayerWindow(LibraryClient api,string id,string title,string url,string audio,double start,object[] subtitles,object[] queue,IDictionary<string,object> media=null) {
            this.media=media ?? new Dictionary<string,object>{{"id",id}};
            this.api=api; profile=api.ProfileId; this.id=id; server=api.Server; this.url=url; this.audio=audio; this.start=start;
            subtitleTracks=subtitles; position=start; autoplay=Preferences.Load().autoplay;
            hintsEnabled=Preferences.Load().playerActionHints;
            episodes=(queue ?? new object[0]).OrderBy(e=>Native.Number(Native.Object(e),"season")).ThenBy(e=>Native.Number(Native.Object(e),"episode")).ThenBy(e=>Native.Text(Native.Object(e),"id")).ToList();
            Text=title+" · Pluxy"; Size=new Size(1180,760); MinimumSize=new Size(700,440); StartPosition=FormStartPosition.CenterParent;
            BackColor=Color.FromArgb(12,16,22); AutoScaleMode=AutoScaleMode.None; Font=new Font("Segoe UI",10); KeyPreview=true;
            using(var graphics=CreateGraphics()) uiScale=graphics.DpiX/96f;
            MinimumSize=new Size(Scale(700),Scale(440));
            string theme=Preferences.Load().theme;
            accent=theme=="ocean"?Color.FromArgb(131,217,229):theme=="midnight"?Color.FromArgb(196,207,235):Color.FromArgb(246,173,132);
            timeline.Accent=accent; status.ForeColor=accent;
            timeline.Height=Scale(22); status.Height=Scale(28);
            previewPanel.Accent=accent;
            previewPanel.Size=new Size(Scale(176),Scale(122)); previewImage.Height=Scale(94); previewTime.Height=Scale(28);
            nextPrompt.Size=new Size(Scale(350),Scale(128)); nextPrompt.Padding=new Padding(Scale(8)); nextPrompt.Font=Font; nextTitle.Height=Scale(50); nextCountdown.Height=Scale(24);
            controls=new PlayerSurface { Accent=accent,Height=Scale(hintsEnabled?102:84),Padding=new Padding(Scale(12),Scale(3),Scale(12),Scale(8)),Anchor=AnchorStyles.Top|AnchorStyles.Left };
            var buttons=new FlowLayoutPanel { Dock=DockStyle.Fill,WrapContents=false,BackColor=controls.BackColor }; actionButtons=buttons;
            pause=ControlButton("Ⅱ","Lire / pause · Espace",async(s,e)=> { if(!ready || closing) return; try { if(Convert.ToBoolean(await mpv.Command("get_property","eof-reached"))) { await Run("seek",0,"absolute+exact"); await Run("set_property","pause",false); } else await Run("cycle","pause"); } catch(Exception error) { Native.Log(error); } });
            buttons.Controls.Add(ControlButton("−10","Reculer de 10 secondes · Flèche gauche",async(s,e)=>await Run("seek",-10,"relative+exact")));
            buttons.Controls.Add(pause); buttons.Controls.Add(ControlButton("+10","Avancer de 10 secondes · Flèche droite",async(s,e)=>await Run("seek",10,"relative+exact")));
            nextButton=ControlButton("▷|","Épisode suivant",async(s,e)=>await NextEpisode()); nextButton.Visible=episodes.Count>1; buttons.Controls.Add(nextButton);
            var episodeMenu=new PlayerMenu(accent); Disposed+=(s,e)=>episodeMenu.Dispose(); var automatic=new ToolStripMenuItem("Enchaîner les épisodes") { Checked=autoplay,CheckOnClick=true };
            automatic.CheckedChanged+=(s,e)=> { autoplay=automatic.Checked; var settings=Preferences.Load(); settings.autoplay=autoplay; settings.Save(); }; episodeMenu.Items.Add(automatic); nextButton.ContextMenuStrip=episodeMenu;
            buttons.Controls.Add(time);
            audioButton=ControlButton("Audio","Choisir la piste audio",async(s,e)=>await ChooseTracks("audio")); buttons.Controls.Add(audioButton);
            subtitlesButton=ControlButton("Sous-titres","Choisir les sous-titres",async(s,e)=>await ChooseTracks("sub")); buttons.Controls.Add(subtitlesButton);
            fullscreenButton=ControlButton("⛶","Plein écran · F11",(s,e)=>ToggleFull()); buttons.Controls.Add(fullscreenButton);
            optionsButton=ControlButton("Options","Options du lecteur",(s,e)=>ShowSkipMenu()); buttons.Controls.Add(optionsButton);
            skipButton=new PlayerButton {Text="Passer l’intro",Accent=accent,Size=new Size(Scale(192),Scale(40)),Visible=false,Font=new Font("Segoe UI",9),AccessibleName="Passer l’intro"};
            skipButton.Click+=async(s,e)=>{ var target=activeSkip; if(target!=null) { await Run("seek",target.endMs/1000.0,"absolute+exact"); UpdateSkip(); } };
            Controls.Add(skipButton);
            volume=new PlayerSlider { Maximum=100,Value=100,Width=Scale(88),Height=Scale(40),Accent=accent,BackColor=controls.BackColor,AccessibleName="Volume",Margin=new Padding(Scale(12),Scale(3),0,0) };
            volume.MouseUp+=async(s,e)=>await Run("set_property","volume",volume.Value); volume.KeyUp+=async(s,e)=>await Run("set_property","volume",volume.Value); buttons.Controls.Add(volume);
            controls.Controls.Add(buttons); controls.Controls.Add(timeline);
            actionHint.Height=Scale(18); actionHint.Font=new Font("Segoe UI",8); actionHint.BackColor=controls.BackColor;
            if(hintsEnabled) controls.Controls.Add(actionHint);
            Controls.Add(screen); Controls.Add(controls); Controls.Add(status);
            previewPanel.Controls.Add(previewImage); previewPanel.Controls.Add(previewTime); Controls.Add(previewPanel);
            previewTimer.Tick+=async(s,e)=>await UpdatePreview(); previewTimer.Start();
            var nextActions=new FlowLayoutPanel { Dock=DockStyle.Bottom,Height=Scale(44),FlowDirection=FlowDirection.RightToLeft,BackColor=Color.Black,WrapContents=false };
            var stay=ControlButton("Rester","Rester sur cet épisode",(s,e)=>CancelNextPrompt());
            nextPlay=ControlButton("Lire maintenant","Lire l'épisode suivant",async(s,e)=>await NextEpisode());
            foreach(var action in new[]{stay,nextPlay}) { ((PlayerButton)action).TextOnly=true; action.BackColor=Color.Black; }
            nextActions.Controls.Add(stay); nextActions.Controls.Add(nextPlay);
            nextPrompt.Controls.Add(nextCountdown); nextPrompt.Controls.Add(nextTitle); nextPrompt.Controls.Add(nextActions);
            nextPrompt.KeyDown+=(s,e)=> { if(e.KeyCode==Keys.Escape) { e.Handled=true; e.SuppressKeyPress=true; CancelNextPrompt(); } else if(e.KeyCode==Keys.F11) { e.Handled=true; ToggleFull(); } };
            nextPrompt.Deactivate+=(s,e)=>CancelNextWhenUnfocused();
            nextPrompt.CancelRequested+=(s,e)=>CancelNextPrompt();
            Disposed+=(s,e)=>nextPrompt.Dispose();
            retryButton=ControlButton("Réessayer","Reprendre au même endroit",async(s,e)=> { recoveryAttempts=0; await Recover(position); }); retryButton.Visible=false; retryButton.Dock=DockStyle.Top; Controls.Add(retryButton); retryButton.BringToFront();
            controls.BringToFront();
            Resize+=(s,e)=>LayoutControls();
            Move+=(s,e)=>LayoutControls();
            LayoutControls();
            time.Margin=new Padding(Scale(10),Scale(14),Scale(12),0); time.Font=new Font("Segoe UI",9);
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
                if(e.KeyCode==Keys.Space) { e.Handled=true; e.SuppressKeyPress=true; await Run("cycle","pause"); }
                if(e.KeyCode==Keys.Right || e.KeyCode==Keys.Left) { e.Handled=true; StartSeek(e.KeyCode==Keys.Right?1:-1); }
            };
            KeyUp+=async(s,e)=> { if((e.KeyCode==Keys.Right || e.KeyCode==Keys.Left) && seekDirection!=0) { e.Handled=true; UpdateSeek(); seekDirection=0; seekTimer.Stop(); seekClock.Stop(); await Run("seek",seekTarget,"absolute+exact"); } };
            seekTimer.Tick+=(s,e)=>UpdateSeek();
            Deactivate+=(s,e)=> { seekDirection=0; seekTimer.Stop(); seekClock.Stop(); CancelNextWhenUnfocused(); };
            Shown+=async(s,e)=> {
                Native.DarkFrame(this);
                try { conversionProfiles=await api.ConversionProfiles(server); } catch(Exception error) { Native.Log(error); }
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
            if(closing) return; closing=true; CancelNextPrompt(); skipLifetime.Cancel(); timer.Stop(); seekTimer.Stop(); mpv.Dispose();
            previewTimer.Stop(); previewLifetime.Cancel(); previewImage.Image=null;
            foreach(var bitmap in previewCache.Values) bitmap.Dispose(); previewCache.Clear();
            try { var save=api.SaveProgress(server,id,position,duration,progressSession,++progressSequence,profile); await Task.WhenAny(save,Task.Delay(2000)); if(save.IsCompleted) await save; else saveObserver=save.ContinueWith(t=>Native.Log(t.Exception),TaskContinuationOptions.OnlyOnFaulted); } catch(Exception error) { Native.Log(error); }
            timer.Dispose(); seekTimer.Dispose(); previewTimer.Dispose(); previewLifetime.Dispose(); skipLifetime.Dispose(); Close();
        }
        protected override bool ProcessCmdKey(ref Message message,Keys keyData) {
            if(keyData==Keys.F11 || keyData==Keys.F) { ToggleFull(); return true; }
            if(keyData==Keys.Escape) { if(nextPrompt.Visible) CancelNextPrompt(); else if(full) ToggleFull(); else Close(); return true; }
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
                    if(conversionQuality!="direct") { await ChangeConversion(conversionQuality,resume,false); return; }
                    CancelNextPrompt(); nextHandled=false;
                    seekGraceUntil=DateTime.UtcNow.AddSeconds(5); restoreReserveAfterSeek=true;
                    await mpv.Command("set_property","cache-pause-wait",3);
                }
                await mpv.Command(command);
                if(command.Length>0 && Convert.ToString(command[0])=="seek") { position=resume; UpdateSkip(); }
            } catch(Exception error) { Native.Log(error); failed=true; }
            if(failed) await Recover(resume);
        }
        private async Task OpenMedia(double resume) {
            activeSkip=null; skipButton.Visible=false;
            ready=false; timer.Stop();
            streamOffset=0;
            if(conversionQuality!="direct" && conversionProfiles.Length>0) {
                var preset=Native.Object(conversionProfiles[conversionIndex]);
                streamOffset=resume;
                url=server+"/stream/convert/"+LibraryClient.ItemPath(id)+"/"+Uri.EscapeDataString(Native.Text(preset,"id"))+"?start_ms="+((long)(resume*1000)).ToString(CultureInfo.InvariantCulture)+"&copy_audio="+(audio=="compatible"?"false":"true");
            } else url=server+"/stream/direct/"+LibraryClient.ItemPath(id);
            await mpv.Start(url,screen.Handle,audio,streamOffset>0?0:resume);
            if(closing) return;
            foreach(var value in subtitleTracks ?? new object[0]) { var sub=Native.Object(value); int index=(int)Native.Number(sub,"index",-1); if(index<0) continue;
                try { await mpv.Command("sub-add",server+"/stream/subs/"+LibraryClient.ItemPath(id)+"/"+index+"?start_ms="+((long)(streamOffset*1000)),"auto",Native.Text(sub,"label"),Native.Text(sub,"lang")); } catch(Exception error) { Native.Log(error); }
            }
            if(wasPaused) await mpv.Command("set_property","pause",true);
            skipChapters=new object[0];
            try {
                var chapters=await mpv.Command("get_property","chapter-list") as object[] ?? new object[0];
                double length=Convert.ToDouble(await mpv.Command("get_property","duration"),CultureInfo.InvariantCulture);
                skipChapters=SkipSegments.Chapters(chapters,length);
            } catch(Exception error) { Native.Log(error); }
            skipLifetime.Cancel(); skipLifetime.Dispose(); skipLifetime=new System.Threading.CancellationTokenSource();
            skipAnalysis=LoadAutomaticSegments(id,server,skipChapters,skipLifetime.Token);
            position=resume; ready=true; retryButton.Visible=false; stalledSince=DateTime.MinValue; healthySince=DateTime.UtcNow; timer.Start();
        }
        private async Task Recover(double resume) {
            if(recovering || closing) return;
            recovering=true; ready=false; timer.Stop(); seekTimer.Stop(); seekDirection=0; dragging=false; position=resume;
            try {
                int retryLimit=conversionQuality=="auto"?3+conversionProfiles.Length:3;
                while(recoveryAttempts<retryLimit && !closing) {
                    status.Visible=true; status.Text="Reconnexion · reprise à "+Clock(resume)+"…"; ShowControls();
                    recoveryAttempts++; mpv.Dispose(); mpv=new MpvSession();
                    try {
                        server=await api.PlaybackServer(server,id); if(closing) return;
                        if(conversionQuality=="auto" && conversionIndex+1<conversionProfiles.Length) conversionIndex++;
                        await OpenMedia(resume); return;
                    } catch(Exception error) { Native.Log(error); status.Text=error.Message; }
                    if(recoveryAttempts<retryLimit) await Task.Delay(1500);
                }
                mpv.Dispose(); status.Visible=true; status.Text="Lecture interrompue à "+Clock(resume)+". Réessayez lorsque le serveur est disponible."; retryButton.Visible=true;
            } finally { recovering=false; }
        }
        private async Task NextEpisode() {
            if(recovering || closing || episodes.Count<2) return;
            int index=episodes.FindIndex(e=>Native.Text(Native.Object(e),"id")==id); if(index<0 || index+1>=episodes.Count) return;
            CancelNextPrompt(); nextHandled=false;
            recovering=true; ready=false; timer.Stop(); bool failed=false;
            try {
                try { await api.SaveProgress(server,id,position,duration,progressSession,++progressSequence,profile); } catch(Exception error) { Native.Log(error); }
                ProgressIds.Add(id);
                var item=Native.Object(episodes[index+1]); media=item; id=Native.Text(item,"id"); position=duration=0; ticks=0; recoveryAttempts=0; wasPaused=false;
                Text=MediaPresentation.PlaybackTitle(item)+" · Pluxy"; url=server+"/stream/direct/"+LibraryClient.ItemPath(id);
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
                position=streamOffset+Convert.ToDouble(pos,CultureInfo.InvariantCulture);
                duration=conversionQuality=="direct"?Convert.ToDouble(dur,CultureInfo.InvariantCulture):Native.Number(media,"duration",0);
                if(duration<=0) duration=streamOffset+Convert.ToDouble(dur,CultureInfo.InvariantCulture);
                UpdateSkip();
                int episodeIndex=episodes.FindIndex(e=>Native.Text(Native.Object(e),"id")==id);
                nextButton.Enabled=episodeIndex>=0 && episodeIndex+1<episodes.Count;
                bool ended=Convert.ToBoolean(await mpv.Command("get_property","eof-reached"));
                if(ended && nextButton.Enabled && !nextHandled) ShowNextPrompt(episodeIndex+1);
                if(nextPrompt.Visible && autoplay) {
                    int remaining=Math.Max(0,NextEpisodeDelaySeconds-(int)nextClock.Elapsed.TotalSeconds);
                    nextCountdown.Text="Lecture dans "+remaining+" s";
                    if(remaining==0 && (ContainsFocus || nextPrompt.ContainsFocus)) { await NextEpisode(); return; }
                }
                if(!dragging && duration>0) timeline.Value=(int)Math.Max(0,Math.Min(10000,position/duration*10000));
                time.Text=Clock(position)+" / "+Clock(duration); pause.Text=Convert.ToBoolean(paused)?"▶":"Ⅱ";
                var pointer=Cursor.Position;
                if(pointer!=lastPointer && Bounds.Contains(pointer)) ShowControls();
                lastPointer=pointer;
                if(ready && !Convert.ToBoolean(paused) && !buffering && !dragging && seekDirection==0 && !controls.RectangleToScreen(controls.ClientRectangle).Contains(pointer) && DateTime.UtcNow-lastInteraction>TimeSpan.FromSeconds(3)) controls.Visible=false;
                if(seekDirection==0) { status.Visible=buffering; if(buffering) status.Text="Chargement en avance…"; }
                if(++ticks%20==0) { try { await api.SaveProgress(server,id,position,duration,progressSession,++progressSequence,profile); } catch(Exception error) { Native.Log(error); } }
            } catch(Exception error) { if(!closing) { Native.Log(error); failed=true; } }
            finally { ticking=false; }
            if(failed) await Recover(position);
        }
        private async Task ChooseTracks(string type) {
            if(!ready) return;
            try {
                var tracks=await mpv.Command("get_property","track-list") as object[];
                var menu=new PlayerMenu(accent,true);
                string selected=Convert.ToString(await mpv.Command("get_property",type=="sub"?"sid":"aid"),CultureInfo.InvariantCulture);
                if(type=="sub") { var off=new ToolStripMenuItem("Désactivés") { Checked=selected=="no" || selected=="False" }; off.Click+=async(s,e)=>await Run("set_property","sid","no"); menu.Items.Add(off); }
                foreach(var value in tracks ?? new object[0]) { var track=Native.Object(value); if(Native.Text(track,"type")!=type) continue;
                    var trackId=(int)Native.Number(track,"id"); var label=Native.Text(track,"title",Native.Text(track,"lang","Piste "+trackId))+" · "+Native.Text(track,"codec");
                    var entry=new ToolStripMenuItem(label) { Checked=selected==trackId.ToString(CultureInfo.InvariantCulture) }; entry.Click+=async(s,e)=>await Run("set_property",type=="sub"?"sid":"aid",trackId); menu.Items.Add(entry);
                }
                if(type=="audio") {
                    menu.Items.Add(new ToolStripSeparator()); string device=Convert.ToString(await mpv.Command("get_property","audio-device"));
                    foreach(var value in await mpv.Command("get_property","audio-device-list") as object[] ?? new object[0]) { var output=Native.Object(value); string name=Native.Text(output,"name"); var entry=new ToolStripMenuItem(AudioDeviceLabel(name,Native.Text(output,"description",name))) { Checked=name==device }; entry.Click+=async(s,e)=>await Run("set_property","audio-device",name); menu.Items.Add(entry); }
                }
                if(menu.Items.Count==0) menu.Items.Add(new ToolStripMenuItem("Aucune piste disponible") { Enabled=false });
                var anchor=type=="audio"?audioButton:subtitlesButton;
                int available=Screen.FromControl(this).WorkingArea.Width;
                menu.MaximumSize=new Size(Math.Max(1,Math.Min(Scale(420),available)),0);
                menu.Show(anchor,new Point(0,0),ToolStripDropDownDirection.AboveRight);
            } catch(Exception error) { Native.Log(error); }
        }
        internal static string AudioDeviceLabel(string name,string description) {
            if(name=="auto") return "Sortie automatique";
            if(description.StartsWith("Default",StringComparison.OrdinalIgnoreCase)) return "Sortie par défaut"+description.Substring(7);
            if(description.Equals("Autoselect device",StringComparison.OrdinalIgnoreCase)) return "Sortie automatique";
            return description;
        }
        private int Scale(int value) { return Math.Max(1,(int)Math.Round(value*uiScale)); }
        private Button ControlButton(string text,string help,EventHandler action) {
            string symbol=text=="−10"?"back":text=="+10"?"forward":text=="Ⅱ"?"pause":text=="▷|"?"next":text=="Audio"?"audio":text=="Sous-titres"?"subtitles":text=="⛶"?"fullscreen":text=="Options"?"more":null;
            var button=new PlayerButton { Text=text,Symbol=symbol,Accent=accent,StrongFocus=Preferences.Load().playerFocusContrast,AutoSize=symbol==null,Size=new Size(Scale(44),Scale(44)),MinimumSize=new Size(Scale(40),Scale(40)) }; button.Click+=action;
            button.Font=new Font("Segoe UI",9); button.Margin=new Padding(Scale(3),Scale(1),Scale(3),0); button.Padding=new Padding(Scale(7),Scale(2),Scale(7),Scale(2));
            button.BackColor=Color.FromArgb(23,24,29); button.FlatAppearance.BorderSize=0;
            button.FlatAppearance.MouseOverBackColor=Color.FromArgb(43,44,51); button.AccessibleName=help;
            if(hintsEnabled) {
                button.GotFocus+=(s,e)=>actionHint.Text=button.AccessibleName;
                button.MouseEnter+=(s,e)=>actionHint.Text=button.AccessibleName;
                button.LostFocus+=(s,e)=>actionHint.Text="";
                button.MouseLeave+=(s,e)=>actionHint.Text=ActiveControl is PlayerButton?ActiveControl.AccessibleName:"";
            }
            return button;
        }
        private void LayoutControls() {
            int width=Math.Min(Scale(800),Math.Max(Scale(100),ClientSize.Width-Scale(24)));
            controls.SetBounds((ClientSize.Width-width)/2,ClientSize.Height-controls.Height-Scale(12),width,controls.Height);
            bool compact=width<Scale(720);
            foreach(Control control in actionButtons.Controls) {
                var button=control as PlayerButton;
                if(button!=null) { int size=Scale(compact?40:44); button.Size=new Size(size,size); button.Margin=new Padding(Scale(compact?2:3),Scale(1),Scale(compact?2:3),0); }
            }
            volume.Width=Scale(compact?60:88); time.Margin=new Padding(Scale(compact?5:10),Scale(14),Scale(compact?6:12),0);
            skipButton.Location=new Point(Math.Max(Scale(12),ClientSize.Width-skipButton.Width-Scale(24)),Math.Max(Scale(12),controls.Top-skipButton.Height-Scale(12)));
            nextPrompt.Location=PointToScreen(new Point(Math.Max(Scale(12),ClientSize.Width-nextPrompt.Width-Scale(24)),Math.Max(Scale(12),controls.Top-nextPrompt.Height-Scale(12))));
        }
        private async Task LoadAutomaticSegments(string ownerId,string ownerServer,object[] nativeChapters,System.Threading.CancellationToken token) {
            try {
                for(int attempt=0;attempt<24;attempt++) {
                    token.ThrowIfCancellationRequested();
                    var chapters=await api.Chapters(ownerServer,ownerId);
                    if(token.IsCancellationRequested || closing || id!=ownerId || server!=ownerServer) return;
                    skipChapters=nativeChapters.Concat(chapters).ToArray();
                    UpdateSkip();
                    if(SkipSegments.Detect(skipChapters,(long)(duration*1000)).Select(segment=>segment.kind).Distinct().Count()==2) return;
                    await Task.Delay(6000,token);
                }
            } catch(OperationCanceledException) {} catch(Exception error) { Native.Log(error); }
        }
        private void UpdateSkip() {
            activeSkip=ready && !closing && !recovering && !dragging && seekDirection==0 && !nextPrompt.Visible?
                SkipSegments.Active(SkipSegments.Detect(skipChapters,(long)(duration*1000)),(long)(position*1000)):null;
            if(activeSkip==null && skipButton.Focused) pause.Focus();
            skipButton.Visible=activeSkip!=null;
            skipButton.Text=activeSkip!=null && activeSkip.kind=="intro"?"Passer l’intro":"Passer le générique";
            skipButton.AccessibleName=skipButton.Text;
            if(skipButton.Visible) skipButton.BringToFront();
        }
        private async Task ChangeConversion(string quality,double resume,bool reset) {
            if(conversionBusy || closing) return;
            conversionBusy=true;
            try {
                conversionQuality=quality;
                if(reset) conversionIndex=quality=="auto"?0:Array.FindIndex(conversionProfiles,p=>Native.Text(Native.Object(p),"id")==quality);
                conversionIndex=Math.Max(0,conversionIndex);
                recoveryAttempts=0; ready=false; timer.Stop(); mpv.Dispose(); mpv=new MpvSession();
                status.Visible=true; status.Text="Application de la qualité · reprise à "+Clock(resume)+"…";
                bool failed=false;
                try { await OpenMedia(resume); } catch(Exception error) { Native.Log(error); failed=true; }
                if(failed) await Recover(resume);
            } finally { conversionBusy=false; }
        }
        private void ShowSkipMenu() {
            if(!ready || closing) return;
            ShowControls();
            var menu=new PlayerMenu(accent);
            menu.Closed+=(s,e)=>{ menu.Dispose(); if(!closing) ShowControls(); };
            menu.Items.Add(new ToolStripMenuItem("Qualité vidéo") { Enabled=false });
            var direct=new ToolStripMenuItem("Originale · aucune conversion") { Checked=conversionQuality=="direct" };
            direct.Click+=async(s,e)=>await ChangeConversion("direct",position,true); menu.Items.Add(direct);
            if(conversionProfiles.Length>0) {
                var automatic=new ToolStripMenuItem("Conversion automatique") { Checked=conversionQuality=="auto" };
                automatic.Click+=async(s,e)=>await ChangeConversion("auto",position,true); menu.Items.Add(automatic);
                foreach(var value in conversionProfiles) {
                    var preset=Native.Object(value); string key=Native.Text(preset,"id");
                    var choice=new ToolStripMenuItem(Native.Text(preset,"label")+" · fixe") { Checked=conversionQuality==key };
                    choice.Click+=async(s,e)=>await ChangeConversion(key,position,true); menu.Items.Add(choice);
                }
            } else menu.Items.Add(new ToolStripMenuItem("Conversion indisponible sur ce serveur") { Enabled=false });
            menu.Items.Add(new ToolStripSeparator());
            menu.Items.Add(new ToolStripMenuItem("Format de l’image") { Enabled=false });
            var original=new ToolStripMenuItem("Original · proportions conservées");
            original.Click+=async(s,e)=>{ await Run("set_property","keepaspect",true); await Run("set_property","panscan",0); }; menu.Items.Add(original);
            var zoom=new ToolStripMenuItem("Zoom · remplir l’écran");
            zoom.Click+=async(s,e)=>{ await Run("set_property","keepaspect",true); await Run("set_property","panscan",1); }; menu.Items.Add(zoom);
            menu.Items.Add(new ToolStripSeparator());
            menu.Items.Add(new ToolStripMenuItem("Intros et génériques : détection automatique") { Enabled=false });
            menu.Show(optionsButton,new Point(0,0),ToolStripDropDownDirection.AboveRight);
        }
        private void ShowNextPrompt(int index) {
            nextHandled=true; var episode=Native.Object(episodes[index]);
            string title=MediaPresentation.EpisodeTitle(episode);
            string code=MediaPresentation.EpisodeCode(episode);
            nextTitle.Text="Épisode suivant\n"+(title.Length>0?title:"Épisode "+Native.Number(episode,"episode"))+" · "+code;
            nextCountdown.Text=autoplay?"Lecture dans "+NextEpisodeDelaySeconds+" s":"Prêt lorsque vous l'êtes";
            nextClock.Restart(); nextPrompt.Show(this); nextPlay.Focus();
        }
        private void CancelNextPrompt() { nextPrompt.Visible=false; nextClock.Reset(); }
        private void CancelNextWhenUnfocused() {
            if(closing || !IsHandleCreated || IsDisposed) return;
            BeginInvoke((Action)(()=> { if(!closing && !ContainsFocus && !nextPrompt.ContainsFocus) CancelNextPrompt(); }));
        }
        private async Task UpdatePreview() {
            if(closing || !ready || duration<=0 || (!dragging && seekDirection==0)) { previewPanel.Visible=false; return; }
            double target=seekDirection!=0?seekTarget:duration*timeline.Value/10000;
            long second=(long)Math.Max(0,target)/10*10; string requestedId=id,requestedServer=server;
            string key=server+":"+id+":"+second;
            if(Convert.ToString(previewPanel.Tag)!=key) { previewPanel.Tag=key; previewImage.Image=null; previewPanel.Visible=false; }
            previewTime.Text=Clock(target);
            Bitmap cached;
            if(previewCache.TryGetValue(key,out cached)) { ShowPreview(cached,target); return; }
            if(previewBusy || previewUnsupported==server+":"+id || DateTime.UtcNow<previewRetryAt) return;
            previewBusy=true;
            try {
                var bytes=await api.SeekPreview(server,id,second,previewLifetime.Token);
                if(closing) return;
                if(bytes==null) { previewRetryAt=DateTime.UtcNow.AddSeconds(1); return; }
                if(bytes.Length==0) { previewUnsupported=requestedServer+":"+requestedId; return; }
                Bitmap bitmap;
                using(var input=new System.IO.MemoryStream(bytes)) using(var image=Image.FromStream(input)) {
                    if(image.Width>640 || image.Height>360) return; bitmap=new Bitmap(image);
                }
                if(previewCache.Count>=12) {
                    var oldest=previewCache.Keys.First(); var old=previewCache[oldest];
                    if(previewImage.Image==old) previewImage.Image=null; previewCache.Remove(oldest); old.Dispose();
                }
                previewCache[key]=bitmap;
                if(Convert.ToString(previewPanel.Tag)==key && (dragging || seekDirection!=0)) ShowPreview(bitmap,target);
            } catch(OperationCanceledException) { } catch(Exception) { previewRetryAt=DateTime.UtcNow.AddSeconds(2); }
            finally { previewBusy=false; }
        }
        private void ShowPreview(Bitmap bitmap,double target) {
            int x=controls.Left+controls.Padding.Left+(int)(timeline.Width*Math.Max(0,Math.Min(1,target/duration)))-previewPanel.Width/2;
            previewPanel.Location=new Point(Math.Max(12,Math.Min(ClientSize.Width-previewPanel.Width-12,x)),Math.Max(12,controls.Top-previewPanel.Height-8));
            previewImage.Image=bitmap; previewPanel.Visible=true; previewPanel.BringToFront();
        }
        private void ShowControls() { lastInteraction=DateTime.UtcNow; controls.Visible=true; controls.BringToFront(); }
        private void ToggleFull() {
            if(!full) { windowBounds=WindowState==FormWindowState.Normal?Bounds:RestoreBounds; windowState=WindowState; full=true; WindowState=FormWindowState.Normal; FormBorderStyle=FormBorderStyle.None; Bounds=Screen.FromControl(this).Bounds; }
            else { full=false; WindowState=FormWindowState.Normal; FormBorderStyle=FormBorderStyle.Sizable; Bounds=windowBounds; WindowState=windowState; }
            fullscreenButton.AccessibleName=full?"Quitter le plein écran · Échap":"Plein écran · F11";
            if(hintsEnabled && fullscreenButton.Focused) actionHint.Text=fullscreenButton.AccessibleName;
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
