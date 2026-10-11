using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using System.Web;
using System.Windows.Forms;
using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.WinForms;

[assembly: System.Reflection.AssemblyTitle("Pluxy")]
[assembly: System.Reflection.AssemblyDescription("Lecteur et serveur multimédia Pluxy")]
[assembly: System.Reflection.AssemblyCompany("Pluxy")]
[assembly: System.Reflection.AssemblyProduct("Pluxy")]
[assembly: System.Reflection.AssemblyVersion("1.8.19.0")]

namespace Pluxy {
    internal static class Program {
        [STAThread] public static int Main(string[] args) {
            System.Net.ServicePointManager.DefaultConnectionLimit=12;
            System.Net.ServicePointManager.SecurityProtocol=System.Net.SecurityProtocolType.Tls12;
            if(args.Contains("--self-test")) return SelfTest.Run(args);
            Application.EnableVisualStyles(); Application.SetCompatibleTextRenderingDefault(false);
            using(var mutex=new Mutex(false,Native.InstanceName)) {
                bool owned; try { owned=mutex.WaitOne(0); } catch(AbandonedMutexException) { owned=true; }
                if(!owned) { try { using(var signal=EventWaitHandle.OpenExisting(Native.InstanceName+"Show")) signal.Set(); } catch { } return 0; }
                try { Application.Run(new MainWindow(args)); return 0; }
                catch(Exception error) { Native.Log(error); MessageBox.Show("Pluxy n'a pas pu démarrer. Réinstallez l'application pour réparer ses composants.","Pluxy",MessageBoxButtons.OK,MessageBoxIcon.Error); return 1; }
                finally { mutex.ReleaseMutex(); }
            }
        }
    }

    internal sealed class MainWindow : Form {
        private const string Origin="https://pluxy.app/";
        private readonly WebView2 browser=new WebView2 { Dock=DockStyle.Fill,DefaultBackgroundColor=Color.FromArgb(11,15,22) };
        private readonly Preferences prefs=Preferences.Load();
        private readonly LibraryClient api=new LibraryClient();
        private readonly ServerController server=new ServerController();
        private readonly List<PlayerWindow> players=new List<PlayerWindow>();
        private readonly MusicSession music;
        private readonly EventWaitHandle signal=new EventWaitHandle(false,EventResetMode.AutoReset,Native.InstanceName+"Show");
        private NotifyIcon tray;
        private ToolStripMenuItem startServer,stopServer,restartServer,serverStatus;
        private readonly System.Windows.Forms.Timer serverMonitor=new System.Windows.Forms.Timer { Interval=4000 };
        private bool checkingServer;
        private readonly System.Windows.Forms.Timer updateMonitor=new System.Windows.Forms.Timer { Interval=60000 };
        private bool checkingUpdate;
        private DateTime nextUpdateCheck=DateTime.MinValue, nextUpdateOffer=DateTime.MinValue;
        private UpdateRelease pendingUpdate;
        private UpdateWindow updateWindow;
        private string serverOperation;
        private bool quitting,choosing,busy,started,balloon,background,shutdownComplete,shuttingDown;
        private Task showSignal;
        private readonly string screenshot;
        private bool fullscreen;
        private Rectangle restoreBounds;
        private FormWindowState restoreState;
        public MainWindow(string[] args) {
            music=new MusicSession(api,state=>Event("music",state));
            Text="Pluxy"; Size=new Size(1280,860); MinimumSize=new Size(860,620); BackColor=Color.FromArgb(11,15,22); StartPosition=FormStartPosition.CenterScreen;
            var icon=Path.Combine(Native.Root,"pluxy.ico"); if(File.Exists(icon)) Icon=new Icon(icon);
            background=args.Contains("--background");
            int shot=Array.IndexOf(args,"--screenshot"); if(shot>=0 && shot+1<args.Length) screenshot=args[shot+1];
            if(args.Contains("--server")) { prefs.mode="server"; prefs.Save(); }
            Controls.Add(browser);
            if(screenshot!=null) { StartPosition=FormStartPosition.Manual; Location=new Point(-30000,-30000); }
            Shown+=async(s,e)=>await Initialize();
            FormClosing+=async(s,e)=> {
                if(e.CloseReason==CloseReason.WindowsShutDown || e.CloseReason==CloseReason.TaskManagerClosing || e.CloseReason==CloseReason.ApplicationExitCall) quitting=true;
                if(!quitting && prefs.mode=="server" && tray!=null) {
                    e.Cancel=true; Hide();
                    if(!balloon) { balloon=true; tray.ShowBalloonTip(2500,"Pluxy","Pluxy reste accessible près de l’horloge.",ToolTipIcon.Info); }
                    return;
                }
                if(shutdownComplete) return;
                e.Cancel=true; if(shuttingDown) return; shuttingDown=true;
                quitting=true; signal.Set();
                await Task.WhenAll(players.ToArray().Select(player=>player.Shutdown()));
                await music.Stop(); music.Dispose();
                serverMonitor.Stop(); serverMonitor.Dispose();
                updateMonitor.Stop(); updateMonitor.Dispose(); if(updateWindow!=null) updateWindow.Close();
                if(tray!=null) { tray.Visible=false; tray.Dispose(); } server.Dispose(); api.Dispose();
                shutdownComplete=true; Close();
            };
        }
        private async Task Initialize() {
            Native.DarkFrame(this);
            try {
                Directory.CreateDirectory(Native.Data);
                var environment=await CoreWebView2Environment.CreateAsync(null,Path.Combine(Native.Data,"WebView"));
                await browser.EnsureCoreWebView2Async(environment);
                var core=browser.CoreWebView2;
                core.Settings.AreDefaultContextMenusEnabled=false; core.Settings.AreDevToolsEnabled=false;
                core.Settings.IsStatusBarEnabled=false; core.Settings.IsZoomControlEnabled=true;
                core.SetVirtualHostNameToFolderMapping("pluxy.app",Path.Combine(Native.Root,"ui"),CoreWebView2HostResourceAccessKind.DenyCors);
                core.AddWebResourceRequestedFilter("https://media.pluxy.invalid/remote*",CoreWebView2WebResourceContext.Image);
                core.WebResourceRequested+=async(s,e)=>await Image(e);
                core.WebMessageReceived+=async(s,e)=>await Message(e);
                core.NewWindowRequested+=(s,e)=> { e.Handled=true; };
                core.NavigationStarting+=(s,e)=> {
                    Uri uri; if(!Uri.TryCreate(e.Uri,UriKind.Absolute,out uri)) { e.Cancel=true; return; }
                    bool own=uri.GetLeftPart(UriPartial.Authority)==Origin.TrimEnd('/');
                    bool configuration=prefs.mode=="server" && server.Address!=null && uri.GetLeftPart(UriPartial.Authority)==server.Address;
                    if(!own && !configuration) e.Cancel=true;
                };
                core.NavigationCompleted+=async(s,e)=> {
                    if(!e.IsSuccess) return;
                    if(!started) { started=true; if(prefs.mode=="server") await ChangeMode("server"); }
                    UpdateServerControls();
                    if(screenshot!=null && browser.Source!=null && browser.Source.Host=="pluxy.app") {
                        await Task.Delay(1800);
                        var result=Native.Object(Native.Json.DeserializeObject(await core.CallDevToolsProtocolMethodAsync("Page.captureScreenshot","{}")));
                        Directory.CreateDirectory(Path.GetDirectoryName(Path.GetFullPath(screenshot)));
                        File.WriteAllBytes(screenshot,Convert.FromBase64String(Native.Text(result,"data"))); quitting=true; Close();
                    }
                };
                tray=new NotifyIcon { Icon=Icon,Text="Pluxy",Visible=true };
                var trayMenu=new ContextMenuStrip(); trayMenu.Items.Add("Afficher Pluxy",null,(s,e)=>Restore());
                serverStatus=new ToolStripMenuItem { Enabled=false };
                trayMenu.Items.Add(serverStatus);
                startServer=new ToolStripMenuItem("Démarrer le serveur",null,async(s,e)=>await ControlServer("serverStart"));
                stopServer=new ToolStripMenuItem("Arrêter le serveur",null,async(s,e)=>await ControlServer("serverStop"));
                restartServer=new ToolStripMenuItem("Redémarrer le serveur",null,async(s,e)=>await ControlServer("serverRestart"));
                trayMenu.Items.AddRange(new ToolStripItem[]{startServer,stopServer,restartServer,new ToolStripSeparator()});
                trayMenu.Items.Add("Changer de mode",null,(s,e)=>ChooseMode()); trayMenu.Items.Add("Quitter Pluxy",null,(s,e)=>Quit());
                trayMenu.Items.Insert(trayMenu.Items.Count-1,new ToolStripMenuItem("Mises à jour",null,(s,e)=>ShowUpdater()));
                trayMenu.Opening+=(s,e)=>UpdateServerControls();
                tray.ContextMenuStrip=trayMenu; tray.DoubleClick+=(s,e)=>Restore();
                serverMonitor.Tick+=async(s,e)=> {
                    if(checkingServer || busy || quitting || prefs.mode!="server") return;
                    checkingServer=true;
                    try { await server.Refresh(); UpdateServerControls(); }
                    finally { checkingServer=false; }
                };
                serverMonitor.Start(); UpdateServerControls();
                updateMonitor.Tick+=async(s,e)=>await CheckAutomaticUpdate(); updateMonitor.Start();
                showSignal=Task.Run(()=> { while(!quitting) { signal.WaitOne(); if(!quitting && IsHandleCreated) BeginInvoke((Action)Restore); } });
                browser.Source=new Uri(Origin+"index.html");
                if(background) Hide();
                await Task.Delay(3000); await CheckAutomaticUpdate();
            } catch(WebView2RuntimeNotFoundException) {
                MessageBox.Show("Le composant d'affichage Microsoft manque. Relancez l'installateur Pluxy : il le préparera automatiquement.","Pluxy",MessageBoxButtons.OK,MessageBoxIcon.Information); quitting=true; Close();
            } catch(Exception error) { Native.Log(error); MessageBox.Show("Impossible d'ouvrir Pluxy. Relancez l'installateur pour réparer l'application.","Pluxy",MessageBoxButtons.OK,MessageBoxIcon.Error); quitting=true; Close(); }
        }
        private void Restore() { Show(); WindowState=FormWindowState.Normal; Activate(); }
        private void Quit() { quitting=true; Close(); }
        private void ShowUpdater(UpdateRelease known=null) {
            if(quitting) return;
            if(updateWindow!=null && !updateWindow.IsDisposed) { updateWindow.Activate(); return; }
            Restore(); nextUpdateOffer=DateTime.UtcNow.AddHours(24);
            prefs.updateOfferAfter=nextUpdateOffer.Ticks; prefs.Save();
            updateWindow=new UpdateWindow(async path=> {
                if(busy) throw new IOException("Attendez la fin de l’opération du serveur avant d’installer.");
                using(var process=Process.Start(new ProcessStartInfo(path,"/SP- /SILENT /NORESTART /PLUXYUPDATE=1 /DIR=\""+Native.Root.TrimEnd(Path.DirectorySeparatorChar)+"\"") {UseShellExecute=true})) {
                    if(process==null) throw new IOException("L’installateur n’a pas pu démarrer.");
                }
                await Task.Yield(); Quit();
            },known);
            updateWindow.Show(this);
        }
        private async Task CheckAutomaticUpdate() {
            if(quitting || checkingUpdate) return;
            if(DateTime.UtcNow>=nextUpdateCheck) {
                checkingUpdate=true;
                try { pendingUpdate=await UpdateChecker.Check(); nextUpdateCheck=DateTime.UtcNow.AddHours(8); }
                catch(Exception) { nextUpdateCheck=DateTime.UtcNow.AddHours(1); }
                finally { checkingUpdate=false; }
            }
            if(!quitting && pendingUpdate!=null && pendingUpdate.available && DateTime.UtcNow>=nextUpdateOffer && DateTime.UtcNow.Ticks>=prefs.updateOfferAfter && Visible && ContainsFocus && players.Count==0 && !busy) ShowUpdater(pendingUpdate);
        }
        private void ChooseMode() {
            if(busy) return; choosing=true; Restore();
            if(browser.CoreWebView2!=null) browser.CoreWebView2.Navigate(Origin+"index.html");
            else browser.Source=new Uri(Origin+"index.html");
        }
        private void UpdateServerControls() {
            if(quitting) return;
            bool active=prefs.mode=="server",running=server.Running;
            string status=serverOperation ?? (running?"Serveur démarré":"Serveur arrêté");
            if(startServer!=null) {
                serverStatus.Visible=startServer.Visible=stopServer.Visible=restartServer.Visible=active;
                serverStatus.Text=status;
                startServer.Enabled=active && !busy && !running;
                stopServer.Enabled=restartServer.Enabled=active && !busy && server.Owned;
                string label=active?"Pluxy · "+status:"Pluxy";
                tray.Text=label.Length>63?label.Substring(0,63):label;
            }
            Event("serverState",new {running=running,busy=busy,canStop=server.Owned,status=status});
        }
        private async Task ControlServer(string command) {
            if(busy || quitting) return;
            if(command!="serverStart" && command!="serverStop" && command!="serverRestart") return;
            busy=true; serverOperation=command=="serverStop"?"Arrêt du serveur…":command=="serverRestart"?"Redémarrage du serveur…":"Démarrage du serveur…";
            UpdateServerControls();
            try {
                if(command=="serverStop") {
                    if(server.Running && !server.Owned) throw new IOException("Ce serveur a été démarré par une autre application.");
                    await Task.Run(()=>server.Stop());
                } else {
                    Action<string> progress=message=> { serverOperation=message; UpdateServerControls(); };
                    if(command=="serverRestart") await server.Restart(progress); else await server.Start(progress);
                    prefs.mode="server"; prefs.Save(); choosing=false;
                    browser.Source=new Uri(server.Address);
                }
            } catch(Exception error) {
                if(quitting) return;
                Native.Log(error); Event("serverError",error.Message);
                if(tray!=null && !Visible) tray.ShowBalloonTip(3500,"Pluxy",error.Message,ToolTipIcon.Error);
            } finally { busy=false; serverOperation=null; UpdateServerControls(); }
        }
        private void Event(string name,object data) { if(browser.CoreWebView2!=null && !quitting) browser.CoreWebView2.PostWebMessageAsJson(Native.Json.Serialize(new{eventName=name,data=data})); }
        private void ToggleFullscreen() {
            if(!fullscreen) {
                restoreBounds=WindowState==FormWindowState.Normal?Bounds:RestoreBounds; restoreState=WindowState;
                fullscreen=true; WindowState=FormWindowState.Normal; FormBorderStyle=FormBorderStyle.None; Bounds=Screen.FromControl(this).Bounds;
            } else {
                fullscreen=false; FormBorderStyle=FormBorderStyle.Sizable; Bounds=restoreBounds; WindowState=restoreState;
            }
            Event("fullscreen",fullscreen);
        }
        protected override void WndProc(ref System.Windows.Forms.Message message) {
            if(message.Msg==0x112 && (message.WParam.ToInt64()&0xFFF0)==0xF030) { if(!fullscreen) ToggleFullscreen(); return; }
            base.WndProc(ref message);
        }
        protected override bool ProcessCmdKey(ref System.Windows.Forms.Message message,Keys keyData) {
            if(keyData==Keys.F11 || keyData==Keys.Escape && fullscreen) { ToggleFullscreen(); return true; }
            return base.ProcessCmdKey(ref message,keyData);
        }
        private async Task ChangeMode(string mode) {
            if(mode!="reader" && mode!="server") throw new ArgumentException("Choisissez Lecteur ou Serveur.");
            if(busy) throw new IOException("La préparation est déjà en cours."); busy=true; UpdateServerControls();
            try {
                if(mode=="server") {
                    await server.Start(message=> { Event("status",message); });
                    prefs.mode="server"; prefs.Save(); choosing=false; browser.Source=new Uri(server.Address);
                } else {
                    foreach(var player in players.ToArray()) player.Close();
                    await Task.Run(()=>server.Stop()); prefs.mode="reader"; prefs.Save(); choosing=false;
                    if(browser.Source==null || browser.Source.Host!="pluxy.app") browser.Source=new Uri(Origin+"index.html");
                }
            } catch(Exception) { if(!quitting) throw; }
            finally { busy=false; UpdateServerControls(); }
        }
        private object ReaderState() { return new {mode=choosing?"":prefs.mode,server=prefs.server,serverName=prefs.serverName,theme=prefs.theme,audio=prefs.audio,playerActionHints=prefs.playerActionHints,playerFocusContrast=prefs.playerFocusContrast,profile=api.ProfileId,multipleProfiles=prefs.multipleProfiles,busy=busy,fullscreen=fullscreen}; }
        private async Task Message(CoreWebView2WebMessageReceivedEventArgs message) {
            Uri source; if(!Uri.TryCreate(message.Source,UriKind.Absolute,out source)) return;
            bool own=source.GetLeftPart(UriPartial.Authority)==Origin.TrimEnd('/');
            bool configuration=prefs.mode=="server" && server.Address!=null && source.GetLeftPart(UriPartial.Authority)==server.Address;
            if(!own && !configuration) return;
            if(configuration) {
                if(message.WebMessageAsJson.Length>4096) return;
                var command=Native.Text(Native.Object(Native.Json.DeserializeObject(message.WebMessageAsJson)),"method");
                if(command=="chooseMode") ChooseMode();
                else if(command=="serverState") UpdateServerControls();
                else if(command=="serverStart" || command=="serverStop" || command=="serverRestart") await ControlServer(command);
                else if(command=="quit") Quit();
                else if(command=="updates") ShowUpdater();
                return;
            }
            string id="";
            try {
                if(message.WebMessageAsJson.Length>1024*1024) return;
                var payload=Native.Object(Native.Json.DeserializeObject(message.WebMessageAsJson)); id=Native.Text(payload,"id");
                var method=Native.Text(payload,"method"); var args=Native.Object(payload.ContainsKey("args")?payload["args"]:null); object result;
                if(method=="state") result=ReaderState();
                else if(method=="fullscreen") { if(!args.ContainsKey("enabled") || Convert.ToBoolean(args["enabled"])!=fullscreen) ToggleFullscreen(); result=fullscreen; }
                else if(method=="chooseMode") { ChooseMode(); result=true; }
                else if(method=="mode") { await ChangeMode(Native.Text(args,"mode")); result=new{mode=prefs.mode}; }
                else if(method=="connect") {
                    if(prefs.mode!="reader") throw new IOException("Choisissez le mode Lecteur.");
                    if(players.Count>0 && LibraryClient.NormalizeServer(Native.Text(args,"url"))!=api.Server) throw new IOException("Fermez le lecteur avant de changer de serveur.");
                    if(!string.IsNullOrEmpty(api.Server)) prefs.StoreReader(api.Server,api.ProfileId);
                    else if(!string.IsNullOrEmpty(prefs.server) && (prefs.readers==null || !prefs.readers.ContainsKey(prefs.server+"\n"+prefs.ProfileFor(prefs.server)))) prefs.StoreReader(prefs.server,prefs.ProfileFor(prefs.server));
                    await music.Stop(); result=await api.Connect(Native.Text(args,"url")); var connection=Native.Object(Native.Json.DeserializeObject(Native.Json.Serialize(result)));
                    prefs.server=Native.Text(connection,"url"); prefs.serverName=Native.Text(connection,"name");
                    api.ProfileId=prefs.ProfileFor(prefs.server); prefs.ApplyReader(prefs.server,api.ProfileId);
                    prefs.Save();
                }
                else if(method=="discover") result=await api.Discover();
                else if(method=="preferences") {
                    if(args.ContainsKey("multipleProfiles") && args["multipleProfiles"] is bool && (bool)args["multipleProfiles"]!=prefs.multipleProfiles) {
                        if(players.Count>0) throw new IOException("Fermez le lecteur avant de modifier les profils.");
                        await music.Stop(); prefs.StoreReader(api.Server,api.ProfileId);
                        prefs.multipleProfiles=(bool)args["multipleProfiles"];
                        if(!prefs.multipleProfiles) { api.ProfileId="default"; prefs.ApplyReader(api.Server,"default"); }
                    }
                    string theme=Native.Text(args,"theme",prefs.theme),audio=Native.Text(args,"audio",prefs.audio);
                    if(!new[]{"coral","ocean","midnight"}.Contains(theme) || !new[]{"auto","passthrough","compatible"}.Contains(audio)) throw new ArgumentException("Réglage inconnu.");
                    prefs.theme=theme; prefs.audio=audio;
                    if(args.ContainsKey("playerActionHints") && args["playerActionHints"] is bool) prefs.playerActionHints=(bool)args["playerActionHints"];
                    if(args.ContainsKey("playerFocusContrast") && args["playerFocusContrast"] is bool) prefs.playerFocusContrast=(bool)args["playerFocusContrast"];
                    prefs.StoreReader(api.Server,api.ProfileId); prefs.Save(); result=true;
                }
                else if(method=="selectProfile") {
                    if(players.Count>0) throw new IOException("Fermez le lecteur avant de changer de profil.");
                    var key=Native.Text(args,"profile");
                    if(!prefs.multipleProfiles && key!="default") throw new IOException("Activez plusieurs profils dans Options.");
                    await music.Stop();
                    var available=await api.Api("profiles",args) as object[] ?? new object[0];
                    if(!available.Any(p=>Native.Text(Native.Object(p),"id")==key)) throw new ArgumentException("Profil indisponible.");
                    prefs.StoreReader(api.Server,api.ProfileId); api.ProfileId=key;
                    if(prefs.profiles==null) prefs.profiles=new Dictionary<string,string>(); prefs.profiles[api.Server]=key;
                    prefs.ApplyReader(api.Server,key); prefs.Save(); result=ReaderState();
                }
                else if(method=="updates") { ShowUpdater(); result=true; }
                else if(method=="backup") {
                    using(var dialog=new SaveFileDialog { Title="Sauvegarder les réglages et l'historique",Filter="Sauvegarde Pluxy (*.zip)|*.zip",FileName="Pluxy-"+DateTime.Now.ToString("yyyy-MM-dd")+".zip" }) {
                        result=false; if(dialog.ShowDialog(this)==DialogResult.OK) { SettingsBackup.Export(dialog.FileName); result=true; }
                    }
                }
                else if(method=="restoreBackup") {
                    result=false;
                    using(var dialog=new OpenFileDialog { Title="Restaurer une sauvegarde Pluxy",Filter="Sauvegarde Pluxy (*.zip)|*.zip" }) {
                        if(dialog.ShowDialog(this)==DialogResult.OK) {
                            var values=SettingsBackup.Read(dialog.FileName);
                            if(MessageBox.Show(this,"Cette sauvegarde remplacera les réglages et l'historique. Une copie de l'état actuel sera conservée. Pluxy se fermera ensuite : rouvrez-le pour appliquer les réglages.","Restaurer Pluxy",MessageBoxButtons.OKCancel,MessageBoxIcon.Information)==DialogResult.OK) {
                                await Task.WhenAll(players.ToArray().Select(player=>player.Shutdown())); await music.Stop(); await Task.Run(()=>server.Stop());
                                await api.SuspendProgress();
                                try { SettingsBackup.Restore(values); result=true; Quit(); }
                                catch { api.ResumeProgress(); throw; }
                            }
                        }
                    }
                }
                else if(method=="trailer") {
                    Uri trailer; string value=Native.Text(args,"url");
                    if(!Uri.TryCreate(value,UriKind.Absolute,out trailer) || trailer.Scheme!="https" || !string.IsNullOrEmpty(trailer.UserInfo)) throw new ArgumentException("Bande-annonce invalide.");
                    string video=trailer.Host=="youtu.be"?trailer.AbsolutePath.Trim('/'):
                        (trailer.Host=="www.youtube.com" || trailer.Host=="youtube.com") && trailer.AbsolutePath=="/watch"?System.Web.HttpUtility.ParseQueryString(trailer.Query)["v"]:null;
                    if(!System.Text.RegularExpressions.Regex.IsMatch(video ?? "","^[A-Za-z0-9_-]{11}$")) throw new ArgumentException("Bande-annonce invalide.");
                    Process.Start(new ProcessStartInfo("https://www.youtube.com/watch?v="+video) { UseShellExecute=true }); result=true;
                }
                else if(method=="musicControl") { await music.Control(Native.Text(args,"action"),Native.Number(args,"value")); result=true; }
                else if(method=="play") {
                    var item=Native.Object(await api.Api("item",args)); string itemId=Native.Text(item,"id");
                    if(Native.Text(item,"kind")=="audio") { await music.Play(item,await api.Group(itemId,"audio"),prefs.audio); result=true; }
                    else {
                    await music.Stop();
                    double start=Math.Max(0,Native.Number(args,"start_ms")/1000);
                    var subtitles=item.ContainsKey("subtitles")?item["subtitles"] as object[]:null;
                    object[] queue=null;
                    if(Native.Text(item,"kind")=="series") queue=await api.Group(itemId,"series");
                    var player=new PlayerWindow(api,itemId,MediaPresentation.PlaybackTitle(item),api.Server+"/stream/direct/"+LibraryClient.ItemPath(itemId),prefs.audio,start,subtitles,queue,item);
                    players.Add(player); player.FormClosed+=(s,e)=> { players.Remove(player); player.ProgressIds.Add(player.MediaId); foreach(var savedId in player.ProgressIds) Event("progressUpdated",new { id=savedId }); }; player.Show(this); result=true; }
                }
                else result=await api.Api(method,args);
                if(!quitting) browser.CoreWebView2.PostWebMessageAsJson(Native.Json.Serialize(new{id=id,result=result}));
            } catch(Exception error) { Native.Log(error); if(!quitting) browser.CoreWebView2.PostWebMessageAsJson(Native.Json.Serialize(new{id=id,error=error.Message})); }
        }
        private async Task Image(CoreWebView2WebResourceRequestedEventArgs e) {
            var deferral=e.GetDeferral();
            try {
                var query=HttpUtility.ParseQueryString(new Uri(e.Request.Uri).Query);
                var stream=await api.Image(query["server"],query["path"]);
                var path=query["path"] ?? ""; string mime=path.Contains("/thumbnail")?"image/jpeg":"application/octet-stream";
                e.Response=browser.CoreWebView2.Environment.CreateWebResourceResponse(stream,200,"OK","Content-Type: "+mime+"\r\nCache-Control: private, max-age=3600");
            } catch { e.Response=browser.CoreWebView2.Environment.CreateWebResourceResponse(new MemoryStream(),404,"Not Found","Cache-Control: no-store"); }
            finally { deferral.Complete(); }
        }
    }
}
