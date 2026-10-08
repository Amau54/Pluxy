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
        private readonly TrackBar timeline=new TrackBar { Dock=DockStyle.Top,Height=30,TickStyle=TickStyle.None,Minimum=0,Maximum=10000,SmallChange=10,LargeChange=100,BackColor=Color.FromArgb(18,24,33) };
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
        private Button pause;
        private Task saveObserver;
        private readonly string progressSession=Guid.NewGuid().ToString("N");
        private long progressSequence;
        public PlayerWindow(LibraryClient api,string id,string title,string url,string audio,double start,object[] subtitles=null) {
            this.api=api; this.id=id; server=api.Server; this.url=url; this.audio=audio; this.start=start;
            Text=title+" · Pluxy"; Size=new Size(1180,760); MinimumSize=new Size(700,440); StartPosition=FormStartPosition.CenterParent;
            BackColor=Color.FromArgb(12,16,22); Font=new Font("Segoe UI",10); KeyPreview=true;
            var controls=new Panel { Dock=DockStyle.Bottom,Height=94,Padding=new Padding(14,2,14,4),BackColor=Color.FromArgb(18,24,33) };
            var buttons=new FlowLayoutPanel { Dock=DockStyle.Fill,WrapContents=false };
            pause=Native.Button("Pause",async(s,e)=> { if(!ready || closing) return; try { if(Convert.ToBoolean(await mpv.Command("get_property","eof-reached"))) { await Run("seek",0,"absolute+exact"); await Run("set_property","pause",false); } else await Run("cycle","pause"); } catch(Exception error) { Native.Log(error); } });
            buttons.Controls.Add(Native.Button("−10 s",async(s,e)=>await Run("seek",-10,"relative+exact")));
            buttons.Controls.Add(pause); buttons.Controls.Add(Native.Button("+10 s",async(s,e)=>await Run("seek",10,"relative+exact")));
            buttons.Controls.Add(Native.Button("Audio",async(s,e)=>await ChooseTracks("audio")));
            buttons.Controls.Add(Native.Button("Sous-titres",async(s,e)=>await ChooseTracks("sub")));
            buttons.Controls.Add(Native.Button("Plein écran",(s,e)=>ToggleFull())); buttons.Controls.Add(time);
            var volume=new TrackBar { Minimum=0,Maximum=100,Value=100,Width=120,Height=36,TickStyle=TickStyle.None,BackColor=controls.BackColor,AccessibleName="Volume" };
            volume.MouseUp+=async(s,e)=>await Run("set_property","volume",volume.Value); volume.KeyUp+=async(s,e)=>await Run("set_property","volume",volume.Value); buttons.Controls.Add(volume);
            controls.Controls.Add(buttons); controls.Controls.Add(timeline);
            Controls.Add(screen); Controls.Add(controls); Controls.Add(status);
            timeline.AccessibleName="Position dans la vidéo";
            timeline.MouseDown+=(s,e)=>dragging=true;
            timeline.MouseUp+=async(s,e)=> { if(ready && duration>0) await Run("seek",duration*timeline.Value/10000,"absolute+exact"); dragging=false; };
            timeline.KeyUp+=async(s,e)=> { if(ready && duration>0) await Run("seek",duration*timeline.Value/10000,"absolute+exact"); };
            screen.DoubleClick+=(s,e)=>ToggleFull();
            KeyDown+=async(s,e)=> {
                if(ActiveControl==timeline) return;
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
            timer.Dispose(); seekTimer.Dispose(); Close();
        }
        protected override bool ProcessCmdKey(ref Message message,Keys keyData) {
            if(ActiveControl!=timeline && (keyData==Keys.Right || keyData==Keys.Left)) {
                StartSeek(keyData==Keys.Right?1:-1); return true;
            }
            return base.ProcessCmdKey(ref message,keyData);
        }
        private async Task Run(params object[] command) { if(!ready || closing) return; try { await mpv.Command(command); } catch(Exception error) { Native.Log(error); status.Visible=true; status.Text="La lecture a été interrompue. Fermez le lecteur et réessayez."; } }
        private void StartSeek(int direction) { if(!ready || closing || duration<=0 || seekDirection!=0) return; seekDirection=direction; seekBase=position; seekClock.Restart(); UpdateSeek(); seekTimer.Start(); }
        private void UpdateSeek() { if(seekDirection==0) return; double delta=SeekAcceleration.Distance(seekClock.ElapsedMilliseconds)*seekDirection; seekTarget=Math.Max(0,Math.Min(duration-.25,seekBase+delta)); status.Visible=true; status.Text=(delta>0?"+":"−")+Clock(Math.Abs(delta))+" · "+SeekAcceleration.Speed(seekClock.ElapsedMilliseconds)+" · "+Clock(seekTarget); }
        private string Clock(double value) { var t=TimeSpan.FromSeconds(Math.Max(0,value)); return (t.TotalHours>=1?t.Hours.ToString("00")+":":"")+t.Minutes.ToString("00")+":"+t.Seconds.ToString("00"); }
        private async Task Tick() {
            if(ticking || closing) return; ticking=true;
            try {
                var pos=await mpv.Command("get_property","time-pos"); var dur=await mpv.Command("get_property","duration"); var paused=await mpv.Command("get_property","pause");
                position=Convert.ToDouble(pos,CultureInfo.InvariantCulture); duration=Convert.ToDouble(dur,CultureInfo.InvariantCulture);
                if(!dragging && duration>0) timeline.Value=(int)Math.Max(0,Math.Min(10000,position/duration*10000));
                time.Text=Clock(position)+" / "+Clock(duration); pause.Text=Convert.ToBoolean(paused)?"Lire":"Pause"; if(seekDirection==0) status.Visible=false;
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
        private void ToggleFull() { full=!full; FormBorderStyle=full?FormBorderStyle.None:FormBorderStyle.Sizable; WindowState=full?FormWindowState.Maximized:FormWindowState.Normal; }
    }
}
