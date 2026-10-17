package io.github.sabopura.morphe.extension.smartnews;

import java.net.URI;

/**
 * Android-free parts of the mission auto-claim: page matching, the injected script and the
 * summary text. The script only talks to SmartNews through the page's own SNClient bridge, so
 * the app attaches its usual authentication to every request.
 */
public final class MissionScript {
    /** JavaScript interface added next to SNClient for logging and the result toast. */
    public static final String INTERFACE = "SabopuraMissions";

    private static final String HOST = "tour.smartnews.com";

    private MissionScript() {
    }

    /** The "My missions" landing page (`/campaign`), not the preview or error pages. */
    public static boolean isMissionPage(String url) {
        if (url == null) {
            return false;
        }
        try {
            URI uri = new URI(url);
            String path = uri.getPath();
            return "https".equalsIgnoreCase(uri.getScheme())
                    && HOST.equalsIgnoreCase(uri.getHost())
                    && ("/campaign".equals(path) || "/campaign/".equals(path));
        } catch (Exception e) {
            return false;
        }
    }

    /** Short toast text; empty when there is nothing worth showing. */
    public static String summary(int points, int amount, int draws, int wins, int failed, boolean japanese) {
        StringBuilder text = new StringBuilder();
        if (points > 0) {
            if (amount > 0) {
                text.append(japanese ? "スマニュー +" + amount + "P を受け取りました" : "+" + amount + " SmartNews points claimed");
            } else {
                text.append(japanese ? "ミッション報酬を " + points + " 件受け取りました"
                        : points + " mission reward" + (points == 1 ? "" : "s") + " claimed");
            }
        }
        if (draws > 0 && wins == 0) {
            if (text.length() > 0) {
                text.append(japanese ? "・" : ", ");
            }
            text.append(japanese ? "抽選 " + draws + " 回（はずれ）" : draws + " draw" + (draws == 1 ? "" : "s") + ", no win");
        }
        if (wins > 0) {
            appendLine(text, japanese
                    ? "抽選に当選しました！マイミッションで確認してください"
                    : "You won a draw! Check My Rewards");
        }
        if (failed > 0) {
            appendLine(text, japanese
                    ? "受け取れなかった報酬が " + failed + " 件あります（ログインなどが必要な場合があります）"
                    : failed + " reward" + (failed == 1 ? "" : "s") + " could not be claimed (sign-in may be required)");
        }
        return text.toString();
    }

    private static void appendLine(StringBuilder text, String line) {
        if (text.length() > 0) {
            text.append('\n');
        }
        text.append(line);
    }

    /**
     * Claims completed mission rewards on the "My missions" page.
     *
     * <p>Primary path: the page's own reward popup. As soon as the page renders it, the popup
     * is hidden and its claim buttons are clicked, so the page runs exactly what a manual tap
     * runs and updates its balance and cards immediately. Lottery draws open the page's lottery
     * dialog, whose fixed 3.6 second animation timer is shortened to zero; a losing result is
     * drawn again when the page offers that, otherwise closed. A winning result stays visible.
     *
     * <p>Fallback: a few seconds later, rewards the popup does not list (for example
     * d POINT rewards) are claimed through the same bridge API the page uses, after the app has
     * synced its locally tracked progress. Only this path reloads the page.
     */
    public static String script(boolean drawLotteries, boolean debug, boolean background) {
        return "(function(){"
                + "if(window.__sabopuraMissions)return;window.__sabopuraMissions=true;"
                + "var B=window.SNClient,R=window." + INTERFACE + ",LOT=" + drawLotteries
                + ",DBG=" + debug + ",BG=" + background + ";"
                + "if(!B||typeof B.postMessageWithId!=='function')return;"
                + "var ST=window.setTimeout.bind(window);"
                + "function log(m){try{R&&R.log(String(m))}catch(e){}}"
                + "function uid(){return(window.crypto&&crypto.randomUUID&&crypto.randomUUID())"
                + "||(Date.now().toString(36)+Math.random().toString(36).slice(2))}"
                + "var mine={},known={},clicked={},s={points:0,amount:0,draws:0,wins:0,failed:0};"
                // ---- Bridge calls (same protocol as the page) ----
                + "function call(action,data,timeout){return new Promise(function(res,rej){"
                + "var id=uid(),t;mine[id]=1;"
                + "function h(e){var d=e&&e.data;if(!d||d.type!=='SNClient'||!d.data)return;var m=d.data;"
                + "if(m.action!==action||(m.id!==undefined&&m.id!==id))return;"
                + "clearTimeout(t);removeEventListener('message',h);"
                + "if(m.error)rej(new Error(action+' '+JSON.stringify(m.error)));else res(m.result)}"
                + "addEventListener('message',h);"
                + "t=ST(function(){removeEventListener('message',h);rej(new Error(action+' timeout'))},timeout||15000);"
                + "B.postMessageWithId(action,id,JSON.stringify(data))})}"
                + "async function api(path,method,query,body){"
                + "var p={path:path,method:method,headers:{'Content-Type':'application/json'},queryParams:query||{}};"
                + "if(body)p.body=body;"
                + "var r=await call('fetch',p,20000)||{},d=r.data;"
                + "if(typeof d==='string'&&d&&/json/i.test(r.contentType||''))d=JSON.parse(d);"
                + "return{status:r.status|0,data:d}}"
                + "function ok(r){return r.status>=200&&r.status<300}"
                + "function brief(r){try{return r.status+' '+JSON.stringify(r.data).slice(0,160)}catch(e){return r.status}}"
                + "function describe(m){var p=m.progress||{},u=m.uiDefinitions||{};"
                + "return m.missionId+' '+m.campaignId+' '+m.triggerId+' '+m.rewardType+' '+p.status+'/'+p.rewardStatus"
                + "+' '+(u.shortTitle||u.title||'')}"
                // ---- Learn mission types from the page's own responses (no extra requests) ----
                + "function absorb(j){if(j&&j.missions&&j.missions.forEach)j.missions.forEach(function(m){"
                + "if(m&&m.missionId)known[m.missionId]=m})}"
                + "function parse(x){if(typeof x!=='string'||x.indexOf('\"missions\"')<0)return x;"
                + "try{return JSON.parse(x)}catch(e){return null}}"
                + "var progress={'stampRally:syncMissionsProgress':1,'stampRally:updateMissionsProgress':1,"
                + "'stampRally:triggerMission':1};"
                + "addEventListener('message',function(e){var d=e&&e.data;if(!d||d.type!=='SNClient'||!d.data)return;"
                + "var m=d.data,r=m.result;"
                + "try{if(r&&m.action==='stampRally:getCampaigns')absorb(parse(r.jsonResponse));"
                + "else if(r&&m.action==='fetch')absorb(parse(r.data))}catch(x){}"
                + "if(m.id!==undefined&&mine[m.id])return;"
                + "if(!BG&&progress[m.action])schedule('page '+m.action,5000)});"
                // ---- Skip the lottery animation: its only delay is a fixed 3600 ms timer ----
                + "if(LOT){var st0=window.setTimeout;window.setTimeout=function(f,d){var a=[].slice.call(arguments);"
                + "if(d===3600&&typeof f==='function'&&q('sn-lottery-modal'))a[1]=0;return st0.apply(window,a)}}"
                // ---- Hide the reward popup / lottery dialog while they are handled ----
                + "var css=document.createElement('style');"
                + "css.textContent='html.sb-popup [data-pixel-id=\"sn-point-mission-complete-popup\"],"
                + "html.sb-lottery [data-pixel-id=\"sn-lottery-modal\"]{opacity:0!important;pointer-events:none!important}';"
                + "(document.head||document.documentElement).appendChild(css);"
                + "var H=document.documentElement;"
                + "function q(id,root){return(root||document).querySelector('[data-pixel-id=\"'+id+'\"]')}"
                + "function later(f,ms){ST(function(){try{f()}catch(e){log('ui: '+e)}},ms)}"
                + "var again=0,closeAt=0,activity=0,reported=0;"
                + "function tick(){"
                + "var modal=q('sn-lottery-modal');"
                + "if(modal){activity=Date.now();"
                + "if(q('sn-lottery-result-win',modal)){H.classList.remove('sb-lottery');"
                + "if(!modal.__sbWin){modal.__sbWin=1;s.draws++;s.wins++;log('lottery: WIN');"
                + "if(BG)later(function(){var c=q('sn-lottery-close',modal);c&&c.click()},500)}return}"
                + "H.classList.add('sb-lottery');"
                + "var lose=q('sn-lottery-result-lose',modal);"
                + "if(lose&&!lose.__sb){lose.__sb=1;s.draws++;log('lottery: LOSE');later(function(){"
                + "var a=q('sn-lottery-draw-again',modal);"
                + "if(a&&again<2&&document.contains(a)){again++;a.click()}else{var c=q('sn-lottery-close',modal);c&&c.click()}},250)}"
                + "var err=q('sn-lottery-error',modal);"
                + "if(err&&!err.__sb){err.__sb=1;s.failed++;log('lottery: error');later(function(){"
                + "var c=q('sn-lottery-error-close',modal);c&&c.click()},250)}"
                + "return}"
                + "H.classList.remove('sb-lottery');"
                + "var pop=q('sn-point-mission-complete-popup');"
                + "if(!pop){H.classList.remove('sb-popup');return}"
                + "var todo=[],blocked=false;"
                + "[].forEach.call(pop.querySelectorAll('[data-pixel-id=\"mission-complete-popup-redeem-btn\"]'),function(b){"
                + "var id=b.getAttribute('data-pixel-mission-id'),t=b.getAttribute('data-pixel-type'),m=known[id];"
                + "if(clicked[id]||b.disabled)return;"
                + "if(t==='redeem'||(t==='draw'&&LOT&&m&&m.rewardType==='LOTTERY'))todo.push(b);else blocked=true});"
                // Leave the popup visible when it lists something the patch must not press.
                + "if(blocked){H.classList.remove('sb-popup')}else{H.classList.add('sb-popup')}"
                + "if(todo.length){var b=todo[0];clicked[b.getAttribute('data-pixel-mission-id')]=1;"
                + "if(b.getAttribute('data-pixel-type')==='redeem'){s.points++;s.amount+=(known[b.getAttribute('data-pixel-mission-id')]||{}).rewardAmount|0}"
                + "log('click '+b.getAttribute('data-pixel-type')+' '+b.getAttribute('data-pixel-mission-id'));"
                + "b.click();activity=Date.now();closeAt=0;later(tick,300);return}"
                + "if(!blocked||BG){if(!closeAt)closeAt=Date.now()+700;"
                + "if(Date.now()>=closeAt){closeAt=0;activity=Date.now();var c=q('mission-complete-popup-close-btn',pop);c&&c.click()}"
                + "else later(tick,750)}}"
                + "new MutationObserver(tick).observe(document.documentElement,{childList:true,subtree:true,attributes:true,"
                + "attributeFilter:['disabled','data-pixel-id']});"
                + "tick();"
                // Toast once things have been quiet for a moment.
                + "function flush(force){if(!force&&(Date.now()-activity<1500||q('sn-lottery-modal')))return;"
                + "var n=s.points+s.draws+s.wins+s.failed;if(n===reported)return;reported=n;"
                + "var key='__sabopuraMissionsFailed',seen=false;"
                + "try{seen=sessionStorage.getItem(key)==='1';if(s.failed)sessionStorage.setItem(key,'1')}catch(e){}"
                + "var failed=seen?0:s.failed;"
                + "if(s.points||s.draws||failed){try{R&&R.report(s.points,s.amount,s.draws,s.wins,failed)}catch(e){}}"
                + "s={points:0,amount:0,draws:0,wins:0,failed:0};reported=0}"
                + "ST(function loop(){flush(false);ST(loop,1000)},1000);"
                // ---- Fallback for rewards the popup does not list ----
                + "async function campaigns(info){"
                + "try{var n=await call('stampRally:getCampaigns',"
                + "{include:'web,progress',referrer:'Mission FE',version:'4.0'},11000);"
                + "if(n&&n.success){var j=parse(n.jsonResponse);if(j&&j.missions)return j}"
                + "log('native campaigns: '+JSON.stringify(n).slice(0,160))}catch(e){log('native campaigns: '+e)}"
                + "var qp={include:'progress,web'};"
                + "if(info&&info.deviceActivationLocalTime)qp.rt=info.deviceActivationLocalTime;"
                + "var c=await api('/api/missions/v1/campaigns','GET',qp);"
                + "if(ok(c)&&c.data&&c.data.missions)return c.data;log('campaigns: '+brief(c));return null}"
                + "async function lottery(m,info){"
                + "var lc=m.rewardParameters&&m.rewardParameters.lotteryCampaignId;if(!lc)return;"
                + "var adId=info.ad&&info.ad.adId;"
                + "if(!adId){s.failed++;log('lottery '+lc+': no adId');return}"
                + "var g=await api('/api/v2/lottery/result','GET',{adId:adId,lotteryCampaignId:lc,platform:info.platform});"
                + "if(!ok(g)){s.failed++;log('lottery result '+lc+': '+brief(g));return}"
                + "var res=g.data&&g.data.result;"
                + "if(res&&(res.code||res.remainingTimesToDraw===0)){log('lottery '+lc+' already drawn: '+res.code);return}"
                + "for(var i=0;i<3;i++){"
                + "var d=await api('/api/v2/lottery/draw','POST',{adId:adId,lotteryCampaignId:lc});"
                + "if(!ok(d)){if(i===0)s.failed++;log('lottery draw '+lc+': '+brief(d));return}"
                + "s.draws++;var r=d.data||{};log('lottery '+lc+': '+r.code);"
                + "if(r.code==='WIN'){s.wins++;return}"
                + "if(!(r.remainingTimesToDraw>0))return}}"
                + "function claimable(m){var p=m&&m.progress;"
                + "return!!p&&p.status==='completed'&&p.rewardStatus!=='redeemed'&&p.rewardStatus!=='redeeming'"
                + "&&!!m.rewardType&&m.rewardType!=='NONE'}"
                + "var info=null,busy=false,rerun=false,timer=0;"
                + "async function pass(why){"
                + "if(busy){rerun=true;return}"
                // Let the popup flow finish first; it is faster and updates the page in place.
                + "if(q('sn-point-mission-complete-popup')||q('sn-lottery-modal')||Date.now()-activity<1500){schedule(why,1500);return}"
                + "busy=true;var claimed=0;"
                + "try{"
                + "if(!info)info=await call('getAppInfo',null)||{};"
                + "try{await call('stampRally:syncMissionsProgress',{version:'4.0'},15000)}catch(e){log('sync: '+e)}"
                + "var c=await campaigns(info);if(!c)return;absorb(c);"
                + "var all=c.missions||[],ms=all.filter(function(m){return claimable(m)&&!clicked[m.missionId]});"
                + "log(why+': claimable '+ms.length+' of '+all.length);"
                + "if(DBG)all.forEach(function(m){if(m)log('mission '+describe(m))});"
                + "for(var i=0;i<ms.length;i++){var m=ms[i];clicked[m.missionId]=1;try{"
                + "if(m.rewardType==='POINTS'||m.rewardType==='D_POINT'){"
                + "var r=await api('/api/missions/v1/missions/redeem','POST',{missionId:m.missionId},{missionId:m.missionId});"
                + "if(ok(r)){s.points++;s.amount+=m.rewardAmount|0;claimed++;log('redeemed '+describe(m))}else{s.failed++;log('redeem '+describe(m)+': '+brief(r))}"
                + "}else if(m.rewardType==='LOTTERY'&&LOT){var before=s.draws;await lottery(m,info);claimed+=s.draws-before}"
                + "else{log('skipped '+describe(m))}"
                + "}catch(e){s.failed++;log(m.missionId+': '+e)}}"
                + "}catch(e){log(why+': '+e)}finally{busy=false;"
                + "if(BG){flush(true);try{R&&R.done()}catch(e){}}}"
                + "if(BG)return;activity=Date.now();"
                // Reload at most every 30 seconds so the page shows what the fallback claimed.
                + "if(claimed){var rk='__sabopuraMissionsReload',last=0;"
                + "try{last=+sessionStorage.getItem(rk)||0}catch(e){}"
                + "if(Date.now()-last>30000){try{sessionStorage.setItem(rk,String(Date.now()))}catch(e){}"
                + "flush(true);location.reload();return}}"
                + "if(rerun){rerun=false;schedule('again',1500)}}"
                + "function schedule(why,delay){clearTimeout(timer);"
                + "timer=ST(function(){pass(why).catch(function(e){log('pass: '+e)})},delay)}"
                + "if(!BG)document.addEventListener('visibilitychange',function(){"
                + "if(document.visibilityState==='visible')schedule('visible',5000)});"
                + "schedule('load',BG?6000:4000)"
                + "})();";
    }
}
