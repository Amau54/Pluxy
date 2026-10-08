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
[assembly: System.Reflection.AssemblyVersion("1.8.2.0")]

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
        private readonly EventWaitHandle signal=new EventWaitHandle(false,EventResetMode.AutoReset,Native.InstanceName+"Show");
        private NotifyIcon tray;
        private bool quitting,choosing,busy,started,balloon,background,shutdownComplete,shuttingDown;
        private Task showSignal;
        private readonly string screenshot;
        private readonly Label modeLabel=new Label { Dock=DockStyle.Left,Width=230,TextAlign=ContentAlignment.MiddleLeft,Padding=new Padding(16,0,0,0),ForeColor=Color.FromArgb(180,193,213),Text="Pluxy",Font=new Font("Segoe UI",10) };
        public MainWindow(string[] args) {
            Text="Pluxy"; Size=new Size(1280,860); MinimumSize=new Size(860,620); BackColor=Color.FromArgb(11,15,22); StartPosition=FormStartPosition.CenterScreen;
            var icon=Path.Combine(Native.Root,"pluxy.ico"); if(File.Exists(icon)) Icon=new Icon(icon);
            background=args.Contains("--background");
            int shot=Array.IndexOf(args,"--screenshot"); if(shot>=0 && shot+1<args.Length) screenshot=args[shot+1];
            if(args.Contains("--server")) { prefs.mode="server"; prefs.Save(); }
            var header=new Panel { Dock=DockStyle.Top,Height=43,BackColor=Color.FromArgb(17,23,32) };
            var menu=Native.Button("Options",(s,e)=>Options()); menu.Dock=DockStyle.Right; menu.Width=110;
            header.Controls.Add(modeLabel); header.Controls.Add(menu); Controls.Add(browser); Controls.Add(header);
            if(screenshot!=null) { StartPosition=FormStartPosition.Manual; Location=new Point(-30000,-30000); }
            Shown+=async(s,e)=>await Initialize();
            FormClosing+=async(s,e)=> {
                if(e.CloseReason==CloseReason.WindowsShutDown || e.CloseReason==CloseReason.TaskManagerClosing || e.CloseReason==CloseReason.ApplicationExitCall) quitting=true;
                if(!quitting && prefs.mode=="server" && server.Owned) {
                    e.Cancel=true; Hide();
                    if(!balloon && tray!=null) { balloon=true; tray.ShowBalloonTip(2500,"Pluxy","Le serveur reste disponible. Retrouvez Pluxy près de l'horloge.",ToolTipIcon.Info); }
                    return;
                }
                if(shutdownComplete) return;
                e.Cancel=true; if(shuttingDown) return; shuttingDown=true;
                quitting=true; signal.Set();
                await Task.WhenAll(players.ToArray().Select(player=>player.Shutdown()));
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
                    if(screenshot!=null && browser.Source!=null && browser.Source.Host=="pluxy.app") {
                        await Task.Delay(1800);
                        var result=Native.Object(Native.Json.DeserializeObject(await core.CallDevToolsProtocolMethodAsync("Page.captureScreenshot","{}")));
                        Directory.CreateDirectory(Path.GetDirectoryName(Path.GetFullPath(screenshot)));
                        File.WriteAllBytes(screenshot,Convert.FromBase64String(Native.Text(result,"data"))); quitting=true; Close();
                    }
                };
                tray=new NotifyIcon { Icon=Icon,Text="Pluxy",Visible=true };
                var trayMenu=new ContextMenuStrip(); trayMenu.Items.Add("Afficher Pluxy",null,(s,e)=>Restore());
                trayMenu.Items.Add("Changer de mode",null,(s,e)=>ChooseMode()); trayMenu.Items.Add("Quitter Pluxy",null,(s,e)=>Quit());
                tray.ContextMenuStrip=trayMenu; tray.DoubleClick+=(s,e)=>Restore();
                showSignal=Task.Run(()=> { while(!quitting) { signal.WaitOne(); if(!quitting && IsHandleCreated) BeginInvoke((Action)Restore); } });
                browser.Source=new Uri(Origin+"index.html");
                if(background) Hide();
            } catch(WebView2RuntimeNotFoundException) {
                MessageBox.Show("Le composant d'affichage Microsoft manque. Relancez l'installateur Pluxy : il le préparera automatiquement.","Pluxy",MessageBoxButtons.OK,MessageBoxIcon.Information); quitting=true; Close();
            } catch(Exception error) { Native.Log(error); MessageBox.Show("Impossible d'ouvrir Pluxy. Relancez l'installateur pour réparer l'application.","Pluxy",MessageBoxButtons.OK,MessageBoxIcon.Error); quitting=true; Close(); }
        }
        private void Restore() { Show(); WindowState=FormWindowState.Normal; Activate(); }
        private void Quit() { quitting=true; Close(); }
        private void ChooseMode() {
            if(busy) return; choosing=true; Restore();
            if(browser.CoreWebView2!=null) browser.CoreWebView2.Navigate(Origin+"index.html");
            else browser.Source=new Uri(Origin+"index.html");
        }
        private void Options() {
            var menu=new ContextMenuStrip(); menu.Items.Add("Changer de mode…",null,(s,e)=>ChooseMode());
            menu.Items.Add("Afficher le dossier des réglages",null,(s,e)=> { Directory.CreateDirectory(Native.Data); Process.Start(new ProcessStartInfo(Native.Data) { UseShellExecute=true }); });
            menu.Items.Add("Quitter Pluxy",null,(s,e)=>Quit()); menu.Show(Cursor.Position);
        }
        private void Event(string name,object data) { if(browser.CoreWebView2!=null && !quitting) browser.CoreWebView2.PostWebMessageAsJson(Native.Json.Serialize(new{eventName=name,data=data})); }
        private async Task ChangeMode(string mode) {
            if(mode!="reader" && mode!="server") throw new ArgumentException("Choisissez Lecteur ou Serveur.");
            if(busy) throw new IOException("La préparation est déjà en cours."); busy=true;
            try {
                if(mode=="server") {
                    await server.Start(message=> { modeLabel.Text="Pluxy · Préparation"; Event("status",message); });
                    prefs.mode="server"; prefs.Save(); choosing=false; modeLabel.Text="Pluxy · Serveur"; browser.Source=new Uri(server.Address);
                } else {
                    foreach(var player in players.ToArray()) player.Close();
                    await Task.Run(()=>server.Stop()); prefs.mode="reader"; prefs.Save(); choosing=false; modeLabel.Text="Pluxy · Lecteur";
                    if(browser.Source==null || browser.Source.Host!="pluxy.app") browser.Source=new Uri(Origin+"index.html");
                }
            } finally { busy=false; }
        }
        private async Task Message(CoreWebView2WebMessageReceivedEventArgs message) {
            Uri source; if(!Uri.TryCreate(message.Source,UriKind.Absolute,out source) || source.GetLeftPart(UriPartial.Authority)!=Origin.TrimEnd('/')) return;
            string id="";
            try {
                if(message.WebMessageAsJson.Length>1024*1024) return;
                var payload=Native.Object(Native.Json.DeserializeObject(message.WebMessageAsJson)); id=Native.Text(payload,"id");
                var method=Native.Text(payload,"method"); var args=Native.Object(payload.ContainsKey("args")?payload["args"]:null); object result;
                if(method=="state") result=new{mode=choosing?"":prefs.mode,server=prefs.server,serverName=prefs.serverName,theme=prefs.theme,audio=prefs.audio,busy=busy};
                else if(method=="chooseMode") { ChooseMode(); result=true; }
                else if(method=="mode") { await ChangeMode(Native.Text(args,"mode")); result=new{mode=prefs.mode}; }
                else if(method=="connect") {
                    if(prefs.mode!="reader") throw new IOException("Choisissez le mode Lecteur.");
                    result=await api.Connect(Native.Text(args,"url")); var connection=Native.Object(Native.Json.DeserializeObject(Native.Json.Serialize(result)));
                    prefs.server=Native.Text(connection,"url"); prefs.serverName=Native.Text(connection,"name"); prefs.Save();
                }
                else if(method=="discover") result=await api.Discover();
                else if(method=="preferences") {
                    string theme=Native.Text(args,"theme",prefs.theme),audio=Native.Text(args,"audio",prefs.audio);
                    if(!new[]{"coral","ocean","midnight"}.Contains(theme) || !new[]{"auto","passthrough","compatible"}.Contains(audio)) throw new ArgumentException("Réglage inconnu.");
                    prefs.theme=theme; prefs.audio=audio; prefs.Save(); result=true;
                }
                else if(method=="play") {
                    var item=Native.Object(await api.Api("item",args)); string itemId=Native.Text(item,"id");
                    double start=Math.Max(0,Native.Number(args,"start_ms")/1000);
                    var subtitles=item.ContainsKey("subtitles")?item["subtitles"] as object[]:null;
                    var player=new PlayerWindow(api,itemId,Native.Text(item,"title"),api.Server+"/stream/direct/"+LibraryClient.ItemPath(itemId),prefs.audio,start,subtitles);
                    players.Add(player); player.FormClosed+=(s,e)=> { players.Remove(player); Event("progressUpdated",true); }; player.Show(this); result=true;
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
