/* Salt's MusicKit PCM port. MusicKit and the browser CDM retain ownership of DRM. */
globalThis.saltApple = {
  error: "", queue: [], bytes: 0, epoch: 0, frame: 0, ready: false, running: false,
  async init(config) {
    try {
      if (!globalThis.MusicKit) await new Promise((resolve, reject) => {
        const script = document.createElement("script");
        script.src = "https://js-cdn.music.apple.com/musickit/v3/musickit.js";
        script.onload = resolve; script.onerror = () => reject(Error("SDK_LOAD"));
        document.head.appendChild(script);
      });
      await MusicKit.configure({developerToken:config.developerToken,
        app:{name:"Salt Extend",build:"0.9.0"}, sourceType:24, suppressErrorDialog:true,
        storefrontId:config.storefront});
      this.mk = MusicKit.getInstance();
      this.mk.musicUserToken = config.userToken;
      this.mk.storefrontId = config.storefront;
      if (!this.mk.isAuthorized) throw Error("LOGIN_REQUIRED");
      this.song = config.song; this.duration = config.duration;
      // MusicKit preallocates 100 audio tags. Attach only the selected player, before it is appended.
      const original = Node.prototype.appendChild;
      const bridge=this;
      Node.prototype.appendChild = function(element) {
        if (element.id === "apple-music-player") bridge.attach(element);
        return original.call(this,element);
      };
      for (const element of document.querySelectorAll("audio")) this.attach(element);
      await this.mk.setQueue({song:config.song});
      await this.mk.play();
      const end = Date.now()+15000;
      while (!this.node && !this.error && Date.now()<end) {
        const element=document.getElementById("apple-music-player");
        if(element)this.attach(element);
        await new Promise(r=>setTimeout(r,20));
      }
      if (!this.node) throw Error("MEDIA_ELEMENT");
      await this.graph;
      if(this.error)throw Error(this.error);
      await this.mk.pause();
      await this.ctx.suspend();
      const item=this.mk.nowPlayingItem;
      if(String(item?.id)!==this.song)throw Error("TRACK_IDENTITY");
      if(![MusicKit.PlaybackType.unencryptedFull,MusicKit.PlaybackType.encryptedFull].includes(item.playbackType))
        throw Error("SUBSCRIPTION_REQUIRED");
      const seconds=this.mk.currentPlaybackDuration;
      if(!Number.isFinite(seconds) || Math.abs(seconds*1000-this.duration)>1500)throw Error("DURATION_MISMATCH");
      this.ready=true;
    } catch (_) { this.error=_.message?.match(/^[A-Z_]+$/)?.[0] || "MUSICKIT_INIT"; }
    return {ready:this.ready,error:this.error};
  },
  attach(element) {
    if(this.media===element)return;
    if(this.media) {this.error="MEDIA_REPLACED";return;}
    this.media=element; element.crossOrigin="anonymous";
    this.ctx=new AudioContext({sampleRate:48000,latencyHint:"playback"});
    const source=this.ctx.createMediaElementSource(element);
    // No connection to the output: Salt is the sole audible playback path.
    this.graph=(async()=>{
      const code=`class SaltPcm extends AudioWorkletProcessor {
        constructor(){super();this.epoch=0;this.on=false;this.data=new Int16Array(8192);this.used=0;
          this.port.onmessage=e=>{
            if(this.epoch!==e.data.epoch){this.epoch=e.data.epoch;this.used=0;}
            this.on=e.data.on;
            if(e.data.flush && this.used){const copy=this.data.slice(0,this.used);this.port.postMessage({epoch:this.epoch,pcm:copy.buffer},[copy.buffer]);this.used=0;}
          };}
        process(inputs){if(!this.on || !inputs[0]?.[0]?.length)return true;
          const channels=inputs[0],left=channels[0],right=channels[1]||left;
          for(let i=0;i<left.length;i++){
            for(const value of [left[i],right[i]])this.data[this.used++]=Math.round(Math.max(-1,Math.min(1,value))*(value<0?32768:32767));
            if(this.used===this.data.length){const copy=this.data.slice();this.port.postMessage({epoch:this.epoch,pcm:copy.buffer},[copy.buffer]);this.used=0;}
          }return true;}}registerProcessor("salt-pcm",SaltPcm);`;
      const url=URL.createObjectURL(new Blob([code],{type:"application/javascript"}));
      try{await this.ctx.audioWorklet.addModule(url);}finally{URL.revokeObjectURL(url);}
      this.node=new AudioWorkletNode(this.ctx,"salt-pcm",{numberOfInputs:1,numberOfOutputs:1,outputChannelCount:[2]});
      source.connect(this.node);this.node.connect(this.ctx.destination);
      // Worklet outputs remain zero; only its authenticated in-process PCM queue is consumed.
      this.node.port.onmessage=e=>{
        if(e.data.epoch!==this.epoch)return;
        const bytes=new Uint8Array(e.data.pcm);
        if(this.bytes+bytes.length>2*1024*1024){this.error="PCM_OVERFLOW";this.pause();return;}
        this.queue.push(bytes);this.bytes+=bytes.length;
      };
      element.addEventListener("ended",()=>this.node.port.postMessage({epoch:this.epoch,on:false,flush:true}));
    })();
    this.graph.catch(()=>{this.error="AUDIO_GRAPH";});
  },
  async seek(frame) {
    if(this.error) return {error:this.error};
    await this.pause();this.epoch++;this.queue=[];this.bytes=0;
    await this.mk.seekToTime(frame/48000);
    this.frame=frame;
    return this.resume();
  },
  async resume() {
    if(this.error)return {error:this.error};
    if(this.frame>=Math.floor(this.duration*48))return {error:""};
    this.running=true;
    await this.mk.play();
    this.node.port.postMessage({epoch:this.epoch,on:true});
    await this.ctx.resume();
    return {error:this.error};
  },
  async pause() {
    this.running=false;
    if(this.mk)await this.mk.pause();
    if(this.ctx)await this.ctx.suspend();
    if(this.node)this.node.port.postMessage({epoch:this.epoch,on:false});
    return {error:this.error};
  },
  pull(maximum) {
    if(this.error)return {error:this.error};
    const size=Math.min(maximum,this.bytes);
    const out=new Uint8Array(size);let written=0;
    while(written<size){const chunk=this.queue[0],count=Math.min(chunk.length,size-written);
      out.set(chunk.subarray(0,count),written);written+=count;
      if(count===chunk.length)this.queue.shift();else this.queue[0]=chunk.subarray(count);}
    this.bytes-=size;this.frame+=size/4;
    let text="";for(const value of out)text+=String.fromCharCode(value);
    return {pcm:btoa(text),error:""};
  }
};
