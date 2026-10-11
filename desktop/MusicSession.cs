using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
using System.Windows.Forms;

namespace Pluxy {
    internal sealed class MusicSession : IDisposable {
        private readonly LibraryClient api;
        private readonly Action<object> publish;
        private readonly Timer timer=new Timer { Interval=1000 };
        private MpvSession engine;
        private object[] queue=new object[0];
        private int index,ticks;
        private long sequence;
        private double position,duration;
        private string server,profile,session,audio;
        private bool busy,polling,paused,disposed;
        private readonly bool automatic;
        public MusicSession(LibraryClient api,Action<object> publish,bool automatic=true) { this.api=api; this.publish=publish; this.automatic=automatic; timer.Tick+=async(s,e)=>await Poll(); }
        public async Task Play(IDictionary<string,object> item,object[] tracks,string output) {
            if(busy) throw new InvalidOperationException("Lecture en préparation.");
            busy=true;
            try {
                await Save(); if(engine!=null) engine.Dispose();
                queue=tracks.OrderBy(e=>Native.Number(Native.Object(e),"disc_number",1)).ThenBy(e=>Native.Number(Native.Object(e),"track_number")).ThenBy(e=>Native.Text(Native.Object(e),"title")).ToArray();
                index=Array.FindIndex(queue,e=>Native.Text(Native.Object(e),"id")==Native.Text(item,"id"));
                if(index<0) { queue=new object[]{item}; index=0; }
                server=api.Server; profile=api.ProfileId; audio=output;
                await Open();
            } catch { if(engine!=null) engine.Dispose(); engine=null; timer.Stop(); queue=new object[0]; publish(new {visible=false}); throw; }
            finally { busy=false; }
        }
        private IDictionary<string,object> Item { get { return queue.Length>0?Native.Object(queue[index]):new Dictionary<string,object>(); } }
        private async Task Open() {
            position=duration=0; ticks=0; sequence=0; paused=false; session=Guid.NewGuid().ToString("N");
            engine=new MpvSession();
            await engine.Start(server+"/stream/direct/"+LibraryClient.ItemPath(Native.Text(Item,"id")),IntPtr.Zero,audio,0,!automatic,true);
            if(automatic) timer.Start(); Publish();
        }
        public async Task Control(string action,double value=0) {
            if(busy || disposed) return;
            if(action=="stop") { await Stop(); return; }
            if(engine==null) return;
            if(action=="pause") await engine.Command("cycle","pause");
            else if(action=="seek") await engine.Command("seek",Math.Max(0,Math.Min(duration,value)),"absolute+exact");
            else if(action=="volume") await engine.Command("set_property","volume",Math.Max(0,Math.Min(100,value)));
            else if(action=="next") await Advance(index+1);
            else if(action=="previous") { if(position>3) await engine.Command("seek",0,"absolute+exact"); else await Advance(Math.Max(0,index-1)); }
            else if(action=="track") await Advance((int)value);
            else throw new ArgumentException("Commande musicale invalide.");
        }
        private async Task Advance(int target) {
            if(target<0 || target>=queue.Length) { await Stop(); return; }
            busy=true;
            try { await Save(); engine.Dispose(); index=target; await Open(); }
            catch { if(engine!=null) engine.Dispose(); engine=null; timer.Stop(); queue=new object[0]; publish(new {visible=false}); throw; }
            finally { busy=false; }
        }
        internal async Task Poll() {
            if(disposed || busy || polling || engine==null) return; polling=true;
            var active=engine;
            try {
                var pos=await active.Command("get_property","time-pos"); var dur=await active.Command("get_property","duration");
                var pause=await active.Command("get_property","pause"); var eof=await active.Command("get_property","eof-reached");
                if(engine!=active || busy) return;
                position=pos==null?position:Convert.ToDouble(pos); duration=dur==null?duration:Convert.ToDouble(dur); paused=pause is bool && (bool)pause;
                if(++ticks%10==0) await Save();
                Publish(); if(eof is bool && (bool)eof) await Advance(index+1);
            } catch(Exception error) { Native.Log(error); if(engine==active && !busy && !disposed) { publish(new { error="Lecture musicale interrompue. Relancez la piste.",visible=true }); timer.Stop(); } }
            finally { polling=false; }
        }
        private void Publish() { publish(new { visible=true,id=Native.Text(Item,"id"),title=Native.Text(Item,"embedded_title",Native.Text(Item,"title")),artist=Native.Text(Item,"artist"),position=position,duration=duration,paused=paused,index=index,queue=queue.Select(e=>new { id=Native.Text(Native.Object(e),"id"),title=Native.Text(Native.Object(e),"embedded_title",Native.Text(Native.Object(e),"title")) }).ToArray() }); }
        private async Task Save() { if(engine!=null && duration>0) try { await api.SaveProgress(server,Native.Text(Item,"id"),position,duration,session,++sequence,profile); } catch(Exception error) { Native.Log(error); } }
        public async Task Stop() { timer.Stop(); await Save(); if(engine!=null) engine.Dispose(); engine=null; queue=new object[0]; publish(new {visible=false}); }
        public void Dispose() { disposed=true; timer.Dispose(); if(engine!=null) engine.Dispose(); }
    }
}
