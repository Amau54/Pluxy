using System;

namespace Pluxy {
    internal sealed class PlaybackBufferPolicy {
        private bool buffering;
        private int interruptions;
        public int ResumeSeconds { get { return interruptions==0?3:Math.Min(20,(int)Math.Ceiling(4*Math.Pow(1.5,interruptions))); } }
        public bool Observe(bool loading,bool paused,bool seeking) {
            bool increase=loading && !buffering && !paused && !seeking;
            buffering=loading;
            if(increase) interruptions=Math.Min(4,interruptions+1);
            return increase;
        }
    }
}
