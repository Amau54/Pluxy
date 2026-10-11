using System;
using System.Drawing;
using System.Threading;
using System.Threading.Tasks;
using System.Windows.Forms;

namespace Pluxy {
    internal sealed class UpdateWindow : Form {
        private readonly Label status=new Label { AutoSize=false,Dock=DockStyle.Fill,ForeColor=Color.FromArgb(190,199,215),Padding=new Padding(0,12,0,8) };
        private readonly ProgressBar progress=new ProgressBar { Dock=DockStyle.Top,Height=5,Visible=false };
        private readonly Button action;
        private readonly Func<string,Task> install;
        private readonly CancellationTokenSource cancel=new CancellationTokenSource();
        private UpdateRelease release;
        private bool downloading;
        public UpdateWindow(Func<string,Task> install,UpdateRelease known=null) {
            this.install=install; release=known;
            Text="Mise à jour de Pluxy"; ClientSize=new Size(520,245); MinimumSize=Size; MaximumSize=Size;
            Font=new Font("Segoe UI",10); BackColor=Color.FromArgb(15,20,29); ForeColor=Color.FromArgb(239,242,248);
            StartPosition=FormStartPosition.CenterParent; MaximizeBox=false; MinimizeBox=false;
            var panel=new TableLayoutPanel { Dock=DockStyle.Fill,Padding=new Padding(24),ColumnCount=1,RowCount=4 };
            panel.RowStyles.Add(new RowStyle(SizeType.Absolute,32)); panel.RowStyles.Add(new RowStyle(SizeType.Percent,100)); panel.RowStyles.Add(new RowStyle(SizeType.Absolute,12)); panel.RowStyles.Add(new RowStyle(SizeType.Absolute,45));
            panel.Controls.Add(new Label {Text="Mises à jour",Dock=DockStyle.Fill,Font=new Font("Segoe UI",17,FontStyle.Bold)},0,0);
            panel.Controls.Add(status,0,1); panel.Controls.Add(progress,0,2);
            var buttons=new FlowLayoutPanel { Dock=DockStyle.Fill,FlowDirection=FlowDirection.RightToLeft };
            action=Native.Button("Vérifier",async(s,e)=> { if(release!=null && release.available) await Download(); else await Check(); });
            var later=Native.Button("Plus tard",(s,e)=>Close()); buttons.Controls.Add(action); buttons.Controls.Add(later); panel.Controls.Add(buttons,0,3); Controls.Add(panel);
            AcceptButton=action; CancelButton=later;
            Shown+=async(s,e)=> { Native.DarkFrame(this); if(release==null) await Check(); else Present(); };
            FormClosing+=(s,e)=>cancel.Cancel();
        }
        private void Present() {
            status.Text=release.available?"Pluxy "+release.version+" est disponible.\nVos réglages et votre bibliothèque seront conservés.":"Pluxy "+UpdateChecker.Normalize(UpdateChecker.Current).ToString(3)+" est à jour.";
            action.Text=release.available?"Télécharger et installer":"Vérifier à nouveau"; action.Enabled=true;
        }
        private async Task Check() {
            action.Enabled=false; status.Text="Recherche de la dernière version sur GitHub…";
            try { release=await UpdateChecker.Check(); if(!IsDisposed) Present(); }
            catch(Exception error) { if(!IsDisposed) { status.Text="Impossible de vérifier la mise à jour.\n"+error.Message; action.Enabled=true; } }
        }
        private async Task Download() {
            if(downloading) return; downloading=true; action.Enabled=false; progress.Visible=true;
            try {
                status.Text="Téléchargement de Pluxy "+release.version+"…";
                var path=await UpdateChecker.Download(release,percent=> { if(!IsDisposed) { progress.Value=percent; status.Text="Téléchargement de Pluxy "+release.version+" · "+percent+" %"; } },cancel.Token);
                if(cancel.IsCancellationRequested) return;
                status.Text="Installation… Confirmez la demande de Windows.";
                await install(path);
            } catch(OperationCanceledException) { if(!IsDisposed) status.Text="Téléchargement annulé. Vous pourrez réessayer."; }
            catch(Exception error) { if(!IsDisposed) { status.Text=error.Message; release=null; action.Text="Vérifier à nouveau"; } }
            finally { downloading=false; if(!IsDisposed) { action.Enabled=true; progress.Visible=false; } }
        }
    }
}
