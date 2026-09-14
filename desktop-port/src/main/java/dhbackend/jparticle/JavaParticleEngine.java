package dhbackend.jparticle;

import com.badlogic.gdx.graphics.g2d.ParticleEmitter;
import com.badlogic.gdx.graphics.g2d.ParticleEmitter.*;
import com.badlogic.gdx.graphics.g2d.BaseSprite;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.utils.Array;
import java.nio.*;
import java.lang.reflect.*;
import java.util.*;

// Moteur de particules REUTILISANT le code du jeu (com.badlogic.gdx.graphics.g2d.ParticleEmitter, present
// dans game-logic(-framed).jar), au lieu de l'emulation unidbg ou d'une reimplementation. Le format binaire
// .np v3 est charge via un ADAPTATEUR (ordre certifie 535/535 du parseur, cf. native/src/np_parser.c) qui
// peuple les champs du ParticleEmitter du jeu -- la simulation (update) et le rendu (sommets 2-couleurs) sont
// ceux du jeu. SS3/SS4 : on execute le code du jeu, on n'ecrit que la glue de format.
//
// getVertices : format natif = 6 floats/sommet (x,y,light,dark,u,v), 4 sommets/particule (quad).
// drawCalls = n*3+1 shorts ; ici un draw call par effet. n retourne = nb de draw calls ; draws[n*3] = nb total
// de sommets (cf. UnidbgVM.effectVertCount).
public final class JavaParticleEngine {
    private static final JavaParticleEngine INSTANCE = new JavaParticleEngine();
    public static JavaParticleEngine get(){ return INSTANCE; }
    // Toggle du backend Java : propriete -Ddh.particlebackend=java, OU env DH_PARTICLEBACKEND=java, OU fichier
    // marqueur <user.home>/.dh_particlebackend (contenu "java"). Le fichier permet de basculer sans toucher au
    // run.bat genere par le launcher (utile pour la verif EN JEU).
    //
    // ⛔ CE BACKEND FAIT CRASHER LE HUB — il n'est PLUS le defaut depuis le 2026-09-15 (cf. BuildManager,
    //    RUN_SH_CLIENT/RUN_BAT_CLIENT). Cause : il n'arrive pas a creer certains effets (ex. le halo de l'icone
    //    PORT du hub, world/env/mainscreen/vfx/mainscreen_port_*_glow.np) ; le jeu se retrouve alors avec un
    //    noeud de scene SANS composant ParticleEffectRenderable. Or MainScreenDisplay.setPersistantGlowAlpha
    //    teste la nullite dans son chemin generique (ifnonnull sur DHSpriteRenderable) mais PAS dans la branche
    //    speciale "features/port/port1-glow", qui itere node.children et appelle
    //    child.getComponent(ParticleEffectRenderable).getTint() directement => NullPointerException. Et comme
    //    MainScreen.updateSceneVisuals boucle sur TOUTES les MainIconType, le hub plante a chaque affichage.
    //    Verifie EN JEU par test differentiel : "java" => crash systematique ; "unidbg" => aucun crash.
    //    ⇒ Avant de reactiver ce backend par defaut, corriger d'abord la CREATION des effets ici.
    //
    // ⚠️ PIEGE DE TEST (m'a fait conclure a tort "ce n'est pas le backend") : la resolution est une CASCADE, et
    //    chaque etape ne teste QUE l'egalite a "java" — elle ne permet pas de DESACTIVER. Poser
    //    DH_PARTICLEBACKEND=unidbg ne suffit donc PAS : on retombe sur le fichier marqueur, qui peut contenir
    //    "java" d'une session precedente. Pour tester unidbg pour de vrai : verifier/ecraser le marqueur
    //    (`printf unidbg > ~/.dh_particlebackend`) ou le supprimer.
    public static boolean flagJava(){
        if ("java".equalsIgnoreCase(System.getProperty("dh.particlebackend"))) return true;
        if ("java".equalsIgnoreCase(System.getenv("DH_PARTICLEBACKEND"))) return true;
        try { java.io.File f = new java.io.File(System.getProperty("user.home"), ".dh_particlebackend");
              if (f.isFile()) { String c = new String(java.nio.file.Files.readAllBytes(f.toPath())).trim();
                  return "java".equalsIgnoreCase(c); } } catch (Throwable ignore) {}
        return false;
    }
    // Active si le toggle est mis ET un resolver d'atlas a ete enregistre (GL requis pour le sprite).
    public static boolean enabled(){ return flagJava() && RESOLVER != null; }

    // Le client (avec contexte GL) fournit le sprite d'atlas + la region pour un (atlasHandle, atlasTag).
    public interface AtlasResolver {
        BaseSprite spriteFor(int atlasHandle, String atlasTag);
        TextureRegion regionFor(int atlasHandle, String atlasTag);
        int pageFor(int atlasHandle, String atlasTag);   // index de page (ordre atlas.getTextures()) ; -1 si absent
        // g303 flipbooks : toutes les frames du tag (triees par index) ; 1 seul element si region unique ; null si absent.
        default TextureRegion[] framesFor(int atlasHandle, String atlasTag) { return null; }
    }
    private static volatile AtlasResolver RESOLVER;
    public static void setResolver(AtlasResolver r){ RESOLVER = r; }

    private static final Map<Integer, Handle> H = new HashMap<>();
    private static int nextId = 1;

    static final class Effect { final Array<ParticleEmitter> emitters = new Array<>();
        final Array<TextureRegion> regions = new Array<>();   // région (uv) PAR émetteur (tag propre)
        final com.badlogic.gdx.utils.IntArray pages = new com.badlogic.gdx.utils.IntArray();  // index de page PAR émetteur
    }
    static final class Handle {
        final Effect eff = new Effect();
        int atlasHandle;
        float x, y, rot;
        TextureRegion region;
        byte[] np;   // octets .np d'origine -> clone = re-parse (le jeu clone les effets pour le pooling)
        String tag="";  // tag du 1er émetteur (diag : quel effet)
        int posLogN=0;
        boolean disposed;   // dispose DIFFERE : le jeu libere l'effet (Effect_dispose) mais le RENDU (getVertices)
                            // continue sur des references obsolettes ; le vrai natif garde le handle rendable
                            // jusqu'a fin des particules (recyclage). On garde donc le Handle vivant tant que ses
                            // particules jouent, et on ne le retire de H qu'a completion (update) -> plus d'effets
                            // invisibles (avant : dispose=H.remove immediat -> 100% des getVertices en handle mort).
    }

    // ---- adaptateur .np v3 -> ParticleEmitter du jeu ----
    private byte[] b; private int pos;
    private int i32(){ int v=((b[pos]&0xff)<<24)|((b[pos+1]&0xff)<<16)|((b[pos+2]&0xff)<<8)|(b[pos+3]&0xff); pos+=4; return v; }
    private float f32(){ return Float.intBitsToFloat(i32()); }
    private boolean bl(){ return b[pos++]!=0; }
    private static void setPriv(Object o,String f,Object v){ try{ Field fl=findF(o.getClass(),f); fl.setAccessible(true); fl.set(o,v);}catch(Exception e){ throw new RuntimeException(f+":"+e);} }
    private static Field findF(Class<?> c,String f){ for(;c!=null;c=c.getSuperclass()){ try{ return c.getDeclaredField(f);}catch(Exception e){} } throw new RuntimeException("no "+f); }
    private static final class Tl{ ScaledNumericValue s; int oa,ob,n; Tl(ScaledNumericValue s,int a,int b,int n){this.s=s;oa=a;ob=b;this.n=n;} }
    private List<Tl> tls;
    private List<Object[]> gts;   // tints (GradientColorValue) à résoudre depuis le pool : {g, n, colorsOff, timelineOff}
    private void rRanged(RangedNumericValue r){ boolean a=bl(); float lo=f32(),hi=f32(); boolean lk=bl(); r.setActive(a); r.setLow(lo,hi); setPriv(r,"lowUsesLinkedRange",lk); }
    private void rScaled(ScaledNumericValue s){ rRanged(s); float hmn=f32(),hmx=f32(); boolean hk=bl(),rel=bl(); s.setHigh(hmn,hmx); setPriv(s,"highUsesLinkedRange",hk); s.setRelative(rel); int n=i32(),oa=i32(),ob=i32(); tls.add(new Tl(s,oa,ob,n)); }
    private void rNumeric(NumericValue nv){ boolean a=bl(); float v=f32(); nv.setActive(a); setPriv(nv,"value",v); }
    // Tint = dégradé de COULEUR : active + [n points, offset couleurs (3n floats RGB), offset timeline (n floats)]
    // dans le pool (même schéma que les timelines des ScaledNumericValue). SANS ça -> couleur packée 0 = transparent
    // -> particules INVISIBLES (c'était le bug). On résout depuis le pool après lecture (comme tls).
    private void rGrad(GradientColorValue g){ boolean a=bl(); g.setActive(a); int n=i32(),oa=i32(),ob=i32(); gts.add(new Object[]{g,n,oa,ob}); }
    private void rSpawn(SpawnShapeValue sv){ boolean a=bl(); int c=b[pos++]&0xff; sv.setActive(a);
        SpawnShape sh=c==0?SpawnShape.point:c==1?SpawnShape.line:c==2?SpawnShape.square:SpawnShape.ellipse; setPriv(sv,"shape",sh);
        if(c==3){ setPriv(sv,"edges",bl()); Object[] sd=SpawnEllipseSide.class.getEnumConstants(); int si=b[pos++]&0xff; setPriv(sv,"side",sd[si%sd.length]); } }
    private ParticleEmitter readEmitter(){
        tls=new ArrayList<>(); gts=new ArrayList<>(); ParticleEmitter em=new ParticleEmitter();
        em.setMinParticleCount(i32()); em.setMaxParticleCount(i32());
        rRanged(em.getDelay()); rRanged(em.getDuration());
        rScaled(em.getEmission()); rScaled(em.getLife()); rScaled(em.getLifeOffset());
        rScaled(em.getTangentialInfluenceValue()); rScaled(em.getCentripetalInfluenceValue()); rScaled(em.getBrownianValue());
        rNumeric(em.getZToYMultiplierValue()); rSpawn(em.getSpawnShape());
        rScaled(em.getSpawnWidth()); rScaled(em.getSpawnHeight()); rScaled(em.getSizeX()); rScaled(em.getSizeY());
        rScaled(em.getVelocity()); rScaled(em.getVelocityZ()); rScaled(em.getAngle()); rScaled(em.getRotation()); rScaled(em.getWind());
        // g301 : le slot ici (avant tint) N'EST PAS la transparency (dump : active=false/0 sur tous les effets).
        // La VRAIE transparency est le slot APRÈS le tint (ordre libGDX standard tint->transparency), cf. plus bas.
        rScaled(em.getGravity()); ScaledNumericValue dPreTint=new ScaledNumericValue(); rScaled(dPreTint);
        ScaledNumericValue d1=new ScaledNumericValue(); rScaled(d1);
        rRanged(em.getCentripetalRadiusValue()); rScaled(em.getCentripetalForceValue()); rScaled(em.getTangentialForceValue()); rRanged(em.getTangentialRadiusValue());
        ScaledNumericValue d2=new ScaledNumericValue(); rScaled(d2);
        rGrad(em.getTint()); rScaled(em.getTransparency());   // g301 : transparency = slot APRÈS le tint (courbe alpha 0..1, high=1, n>1)
        setPriv(em,"frameDuration",f32());
        boolean att=bl(),cont=bl(),ali=bl(); int fl=b[pos++]&0xff; boolean beh=bl();
        setPriv(em,"attached",att); setPriv(em,"continuous",cont); setPriv(em,"aligned",ali); setPriv(em,"additive",(fl&1)!=0); setPriv(em,"behind",beh);
        int poolSize=i32(),tagLen=i32(); float[] pool=new float[poolSize]; for(int i=0;i<poolSize;i++) pool[i]=f32();
        lastTag = tagLen>0 ? new String(b, pos, tagLen, java.nio.charset.StandardCharsets.UTF_8) : "";
        pos+=tagLen;
        for(Tl t:tls){ if(t.n>0 && t.oa+t.n<=poolSize && t.ob+t.n<=poolSize){ t.s.setTimeline(Arrays.copyOfRange(pool,t.oa,t.oa+t.n)); t.s.setScaling(Arrays.copyOfRange(pool,t.ob,t.ob+t.n)); } }
        // tint : comme les ScaledNumericValue (tls), le 1er offset (oa) = TIMELINE (n floats), le 2e (ob) = DONNÉES
        // = COULEURS (3n floats RGB). g301 : j'avais inversé (couleurs @oa) -> couleurs décalées d'un cran
        // (R prenait la valeur timeline=0, canaux glissés) -> tint faux. Corrigé : couleurs @ob, timeline @oa.
        for(Object[] g:gts){ GradientColorValue gc=(GradientColorValue)g[0]; int n=(int)g[1],oa=(int)g[2],ob=(int)g[3];
            if(n>0 && ob+3*n<=poolSize && oa+n<=poolSize){ float[] cols=Arrays.copyOfRange(pool,ob,ob+3*n), tml=Arrays.copyOfRange(pool,oa,oa+n);
                gc.setColors(cols); setPriv(gc,"timeline",tml);
                if(DBG && !dbgTintDone){ dbgTintDone=true; System.err.println("[jparticle] TINT n="+n+" colors="+Arrays.toString(cols)+" timeline="+Arrays.toString(tml)); } } }
        if(NPDUMP && lastTag!=null && lastTag.matches("(?i).*(snow|flake|punch|impact|mist|spark|ice|energy|frost).*") && npdumpSeen.add(lastTag)){
            StringBuilder sb=new StringBuilder("[NPDUMP] tag='"+lastTag+"' poolSize="+poolSize+"\n  pool=[");
            for(int i=0;i<poolSize && i<64;i++){ sb.append(String.format("%d:%.4g ", i, pool[i])); } sb.append("]\n");
            for(Object[] g:gts){ sb.append("  GRAD n="+(int)g[1]+" colorsOff(oa)="+(int)g[2]+" timelineOff(ob)="+(int)g[3]+"\n"); }
            int ti=0; for(Tl t:tls){ sb.append("  SCL["+(ti++)+"] active="+getPriv(t.s,"active")+" low=("+getPriv(t.s,"lowMin")+","+getPriv(t.s,"lowMax")+") high=("+getPriv(t.s,"highMin")+","+getPriv(t.s,"highMax")+") tOff="+t.oa+" sOff="+t.ob+" n="+t.n+"\n"); }
            try{ ScaledNumericValue tr=em.getTransparency(); sb.append("  TRANSP active="+getPriv(tr,"active")+" low=("+getPriv(tr,"lowMin")+","+getPriv(tr,"lowMax")+") high=("+getPriv(tr,"highMin")+","+getPriv(tr,"highMax")+") scaling="+Arrays.toString((float[])getPriv(tr,"scaling"))+" timeline="+Arrays.toString((float[])getPriv(tr,"timeline"))+"\n"); }catch(Throwable t){}
            System.err.println(sb.toString());
        }
        return em;
    }
    private String lastTag = "";

    // ---- API facon cparticle.Native ----
    // Le sprite d'atlas (TwoColorAtlasSprite/AtlasSprite) et la region (uv) sont resolus par le RESOLVER du
    // client (contexte GL requis) depuis (atlasHandle, atlasTag lu du .np par emetteur). §1/§4 glue.
    public synchronized int create(byte[] np, int atlasHandle){
        b=np; pos=0;
        if(np.length<6||np[0]!=0||np[1]!=3) throw new RuntimeException("np: pas v3");
        pos=2; int nE=i32();
        Handle h=new Handle(); h.atlasHandle=atlasHandle; h.np=np;
        AtlasResolver r=RESOLVER;
        for(int i=0;i<nE;i++){ ParticleEmitter em=readEmitter();
            if(i==0) h.tag=lastTag;
            TextureRegion reg=null; int page=0;
            if(r!=null){
                BaseSprite sprite=r.spriteFor(atlasHandle, lastTag);
                if(sprite!=null){ try{ em.setSprite(sprite); }catch(Throwable t){ setPriv(em,"sprite",sprite); } }
                reg=r.regionFor(atlasHandle, lastTag);
                int p=r.pageFor(atlasHandle, lastTag); if(p>=0) page=p;
                if(h.region==null) h.region=reg;
                // g303 FLIPBOOK : si le tag a plusieurs frames, construire l'Animation du jeu -> son update fait
                // defiler particle.region (sinon particules figees sur 1 frame, souvent quasi vide = invisibles).
                try { TextureRegion[] frames=r.framesFor(atlasHandle, lastTag);
                    if(frames!=null && frames.length>1){
                        float fd; try{ fd=em.getFrameDuration(); }catch(Throwable t){ fd=0f; } if(fd<=0f) fd=0.05f;
                        com.badlogic.gdx.utils.Array<TextureRegion> arr=new com.badlogic.gdx.utils.Array<>(frames);
                        em.setAnimation(new com.badlogic.gdx.graphics.g2d.Animation<TextureRegion>(fd, arr));
                        if(DBG) System.err.println("[jparticle] ANIM tag='"+lastTag+"' frames="+frames.length+" fd="+fd);
                    }
                } catch(Throwable t){ if(DBG) System.err.println("[jparticle] anim echec '"+lastTag+"': "+t); }
            }
            h.eff.emitters.add(em); h.eff.regions.add(reg); h.eff.pages.add(page); }
        int id=nextId++; H.put(id,h);
        if (DBG) { Object tex=null; try{ if(h.region!=null) tex=h.region.getTexture(); }catch(Throwable t){}
            System.err.println("[jparticle] create id="+id+" emitters="+h.eff.emitters.size+" atlas="+atlasHandle
                +" resolver="+(r==null?"NULL":"ok")+" region="+(h.region==null?"NULL":"ok")+" tex="+(tex==null?"NULL":tex)
                +" lastTag='"+lastTag+"'"); }
        return id;
    }
    static final boolean DBG = "1".equals(System.getProperty("dh.jparticle.debug"));
    static final boolean NPDUMP = "1".equals(System.getProperty("dh.jparticle.npdump"));
    private static final java.util.Set<String> npdumpSeen = java.util.Collections.synchronizedSet(new java.util.HashSet<>());
    private static int gvCalls=0, gvNonEmpty=0, gvLastLog=-1;
    public synchronized void start(int id){ Handle h=H.get(id); if(h==null) return; for(ParticleEmitter e:h.eff.emitters){ e.setPosition(h.x,h.y); e.start(); } }
    public synchronized boolean update(int id, float dt){ Handle h=H.get(id); if(h==null) return false; boolean any=false, allComplete=true;
        // DTDBG : mesure de la vitesse de vieillissement (dt + life/currentLife d'une particule de combat) — vérifie
        // si le dt est dans la bonne unité (secondes pour ParticleEmitter libGDX qui fait dt*1000 en interne).
        Object lpBefore=null;
        if(DTDBG && h.tag!=null && dtLogN<50 && h.tag.matches("(?i).*(snow|impact|punch|spark|mist|energy|icicle|frost).*")){
            for(ParticleEmitter e:h.eff.emitters){ Object pa=getPriv(e,"particles"); if(pa instanceof Object[]){ for(Object p:(Object[])pa){ if(p!=null){ lpBefore=p; break; } } } if(lpBefore!=null) break; } }
        for(ParticleEmitter e:h.eff.emitters){ e.update(dt); if(e.getActiveCount()>0) any=true; if(!e.isComplete()) allComplete=false; }
        if(DTDBG && lpBefore!=null){ dtLogN++;
            System.err.println("[jparticle] UPD dt="+dt+" tag='"+h.tag+"' active="+(any?"Y":"n")+" life(après)="+getPriv(lpBefore,"life")+"/"+getPriv(lpBefore,"currentLife")); }
        if(h.disposed && allComplete && !any){ H.remove(id); if(DBG) System.err.println("[jparticle] cleanup id="+id+" (disposed+complete)"); }
        return any; }
    static int dtLogN=0;
    static final boolean DTDBG = "1".equals(System.getProperty("dh.jparticle.dtdbg"));
    public synchronized void setPosition(int id,float x,float y){ Handle h=H.get(id); if(h==null) return; h.x=x; h.y=y; for(ParticleEmitter e:h.eff.emitters) e.setPosition(x,y);
        if(DBG && h.posLogN<5 && h.tag!=null && h.tag.matches("(?i).*(snow|flake|punch|impact|splash|ice|energy|frost|hand_mist|icewave|snowball).*")){
            h.posLogN++; System.err.println("[jparticle] SETPOS id="+id+" tag='"+h.tag+"' pos=("+x+","+y+")"); } }
    public synchronized void setRotation(int id,float r){ Handle h=H.get(id); if(h==null) return; h.rot=r; }
    public synchronized void dispose(int id){ Handle h=H.get(id);
        if(h!=null){ h.disposed=true; for(ParticleEmitter e:h.eff.emitters){ try{ setPriv(e,"continuous",false); }catch(Throwable t){} } }
        // NE PAS retirer de H : le rendu (getVertices) suit sur des refs obsoletes ; le Handle vit jusqu'a
        // completion des particules (retire dans update()). Mirror du dispose differe du vrai natif.
    }
    // Clone (pooling du jeu) : re-parse le .np d'origine dans un nouveau handle, recopie position/rotation.
    public synchronized int clone(int id){
        Handle src=H.get(id); if(src==null||src.np==null){ if(DBG) System.err.println("[jparticle] clone src="+id+" -> 0 (src absent)"); return 0; }
        int nid=create(src.np, src.atlasHandle);
        if(DBG) System.err.println("[jparticle] clone src="+id+" -> "+nid);
        Handle nh=H.get(nid); if(nh!=null){ nh.x=src.x; nh.y=src.y; nh.rot=src.rot; for(ParticleEmitter e:nh.eff.emitters) e.setPosition(src.x,src.y); }
        return nid;
    }
    public synchronized int activeCount(int id){ Handle h=H.get(id); if(h==null) return 0; int n=0; for(ParticleEmitter e:h.eff.emitters) n+=e.getActiveCount(); return n; }
    public synchronized String tagOf(int id){ Handle h=H.get(id); return h==null?null:h.tag; }   // diag : tag du 1er émetteur (comparaison unidbg)

    // Remplit verts (6 floats/sommet) + draws (n*3+1 shorts) ; retourne n (draw calls).
    private static final boolean TESTQUAD = "1".equals(System.getProperty("dh.jparticle.testquad"));
    private static final boolean FORCEWHITE = "1".equals(System.getProperty("dh.jparticle.forcewhite"));
    private static final boolean CALIB = "1".equals(System.getProperty("dh.jparticle.calib"));
    private static final boolean PDBG = "1".equals(System.getProperty("dh.jparticle.pdbg"));
    private static final boolean OPAQUE = "1".equals(System.getProperty("dh.jparticle.opaque"));
    private static final java.util.Set<String> pdbgSeen = java.util.Collections.synchronizedSet(new java.util.HashSet<>());
    private static final boolean FORCEBIG = "1".equals(System.getProperty("dh.jparticle.forcebig"));
    private static int gvFound=0, gvNull=0, gvNonEmptyReal=0, gvAlphaPos=0, gvMaxAlpha=0;
    private static float bbMinX=1e9f,bbMaxX=-1e9f,bbMinY=1e9f,bbMaxY=-1e9f;
    private static float emMinX=1e9f,emMaxX=-1e9f,emMinY=1e9f,emMaxY=-1e9f;
    public synchronized int getVertices(int id, FloatBuffer verts, ShortBuffer draws){
        Handle h=H.get(id);
        if (DBG) { if(h==null) gvNull++; else gvFound++;
            if ((gvFound+gvNull)%100==0) System.err.println("[jparticle] getVertices STATS found="+gvFound+" null="+gvNull+" nonEmpty="+gvNonEmptyReal+" alphaPosQuads="+gvAlphaPos+" maxAlpha="+gvMaxAlpha
                +" bbox=["+(int)bbMinX+","+(int)bbMinY+" .. "+(int)bbMaxX+","+(int)bbMaxY+"]"
                +" emPos=["+(int)emMinX+","+(int)emMinY+" .. "+(int)emMaxX+","+(int)emMaxY+"]");
              bbMinX=1e9f;bbMaxX=-1e9f;bbMinY=1e9f;bbMaxY=-1e9f; emMinX=1e9f;emMaxX=-1e9f;emMinY=1e9f;emMaxY=-1e9f; gvAlphaPos=0;gvMaxAlpha=0; }
        if(h==null) return 0;
        // CALIBRATION monde->écran (-Ddh.jparticle.calib=1) : 2 quads à positions monde FIXES connues
        // (A=(2000,500), B=(8000,1000)), taille 120, UV région[0]. On lit où ils atterrissent à l'écran.
        if(CALIB && h.eff.emitters.size>0){
            TextureRegion reg = h.eff.regions.size>0? (TextureRegion)h.eff.regions.get(0): h.region;
            int page = h.eff.pages.size>0? Math.max(0,h.eff.pages.get(0)):0;
            if(reg!=null){
                float u=reg.getU(),v=reg.getV(),u2=reg.getU2(),v2=reg.getV2();
                float wht=com.badlogic.gdx.graphics.Color.WHITE.toFloatBits();
                // grille : i=0..6 -> x=i*2000, y=1500, taille = 40 + i*18 (identifie x par la taille croissante)
                int N=7; verts.clear(); if(draws!=null) draws.clear();
                for(int gi=0; gi<N; gi++){ float cx=gi*2000f, cy=1500f, s=40f+gi*18f;
                    float[] qx={cx-s,cx-s,cx+s,cx+s}, qy={cy-s,cy+s,cy+s,cy-s}, uu={u,u,u2,u2}, vv={v2,v,v,v2};
                    for(int i=0;i<4;i++){ verts.put(qx[i]);verts.put(qy[i]);verts.put(wht);verts.put(0f);verts.put(uu[i]);verts.put(vv[i]); }
                    if(draws!=null){ draws.put((short)6); draws.put((short)0); draws.put((short)page); } }
                if(draws!=null){ draws.put((short)(N*4)); draws.flip(); }
                verts.flip();
                return N;
            }
        }
        // TEST ISOLATION (-Ddh.jparticle.testquad=1) : un seul gros quad blanc opaque à la position de
        // l'émetteur (qui mappe à l'écran), UV d'une vraie région. Prouve pipeline draw+coords+texture.
        if(TESTQUAD && h.eff.emitters.size>0){
            ParticleEmitter e0=(ParticleEmitter)h.eff.emitters.get(0); float ex=fp(e0,"x"), ey=fp(e0,"y");
            TextureRegion reg = h.eff.regions.size>0? (TextureRegion)h.eff.regions.get(0): h.region;
            int page = h.eff.pages.size>0? Math.max(0,h.eff.pages.get(0)):0;
            if(reg!=null && (ex!=0f||ey!=0f)){
                float u=reg.getU(),v=reg.getV(),u2=reg.getU2(),v2=reg.getV2();
                float wht=com.badlogic.gdx.graphics.Color.WHITE.toFloatBits(); float s=600f;
                float[] qx={ex-s,ex-s,ex+s,ex+s}, qy={ey-s,ey+s,ey+s,ey-s}, uu={u,u,u2,u2}, vv={v2,v,v,v2};
                verts.clear(); for(int i=0;i<4;i++){ verts.put(qx[i]);verts.put(qy[i]);verts.put(wht);verts.put(0f);verts.put(uu[i]);verts.put(vv[i]); } verts.flip();
                if(draws!=null){ draws.clear(); draws.put((short)6); draws.put((short)0); draws.put((short)page); draws.put((short)4); draws.flip(); }
                return 1;
            }
        }
        verts.clear(); if(draws!=null) draws.clear();
        // Format drawCalls attendu par NativeParticleEffectRenderer (relevé au bytecode) : 3 shorts/draw-call =
        //   [count = nb d'INDICES (6/quad, mesh indexé), blendFlags (&1=srcONE, &2=dstONE, &4=multiply DST_COLOR),
        //    pageIndex (index dans atlas.getTextures())], PUIS 1 short = nb TOTAL de sommets (effectVertCount).
        //   Mesh.render(GL_TRIANGLES, offset, count) avec offset d'indices accumulé. Un draw-call par émetteur
        //   (blend/page propres). (Avant : [0,0,vcount,vcount] -> vcount lu comme pageIndex -> crash Array.get.)
        int totalVerts=0, n=0;
        for(int ei=0; ei<h.eff.emitters.size; ei++){
            ParticleEmitter em=(ParticleEmitter)h.eff.emitters.get(ei);
            if(DBG){ float ex=fp(em,"x"), ey=fp(em,"y"); if(ex<emMinX)emMinX=ex; if(ex>emMaxX)emMaxX=ex; if(ey<emMinY)emMinY=ey; if(ey>emMaxY)emMaxY=ey; }
            TextureRegion reg = (ei<h.eff.regions.size && h.eff.regions.get(ei)!=null) ? (TextureRegion)h.eff.regions.get(ei) : h.region;
            int page = ei<h.eff.pages.size ? h.eff.pages.get(ei) : 0;
            Object parts=getPriv(em,"particles"); if(!(parts instanceof Object[])) continue;
            int emVerts=0; int emStart=verts.position();
            for(Object p:(Object[])parts){ if(p==null) continue; if(emitQuad(p,reg,verts)) emVerts+=4; }
            if(emVerts>0){
                boolean additive = Boolean.TRUE.equals(getPriv(em,"additive"));
                if(draws!=null){ draws.put((short)(emVerts/4*6)); draws.put((short)(additive?3:0)); draws.put((short)page); }
                totalVerts+=emVerts; n++;
                // PDBG (-Ddh.jparticle.pdbg=1) : dump COMPLET d'une particule de combat (RGB decode + UV + region + blend)
                if(PDBG && h.tag!=null && h.tag.matches("(?i).*(snow|flake|punch|impact|splash|ice|energy|frost|snowball|blast|spark).*") && pdbgSeen.add(h.tag)){
                    // 1ère particule non-nulle : dump transparency + tint + sprite color
                    Object p0=null; for(Object pp:(Object[])parts){ if(pp!=null){ p0=pp; break; } }
                    String pd="?"; if(p0!=null){ Object tint=getPriv(p0,"tint"); String ts="?";
                        if(tint instanceof float[]){ float[] tf=(float[])tint; ts=java.util.Arrays.toString(tf); }
                        pd="p.transparency="+fp(p0,"transparency")+" p.tint="+ts; }
                    // emetteur transparencyValue
                    String et="?"; try{ Object tv=em.getClass().getMethod("getTransparency").invoke(em);
                        Object sc=tv.getClass().getMethod("getScaling").invoke(tv); Object tl=tv.getClass().getMethod("getTimeline").invoke(tv);
                        et="high=("+getPriv(tv,"highMin")+","+getPriv(tv,"highMax")+") scaling="+(sc instanceof float[]?java.util.Arrays.toString((float[])sc):"?")+" timeline="+(tl instanceof float[]?java.util.Arrays.toString((float[])tl):"?"); }catch(Throwable t){ et="err:"+t; }
                    System.err.println("[jparticle] PALPHA tag='"+h.tag+"' "+pd+" | transp:"+et);
                    int lb=Float.floatToRawIntBits(verts.get(emStart+2)), db=Float.floatToRawIntBits(verts.get(emStart+3));
                    String rt="?"; try{ if(reg!=null && reg.getTexture()!=null) rt=reg.getTexture().getWidth()+"x"+reg.getTexture().getHeight()+"@"+System.identityHashCode(reg.getTexture()); }catch(Throwable t){}
                    System.err.println("[jparticle] PDBG tag='"+h.tag+"' em="+ei+" page="+page+" additive="+additive
                        +" light=RGBA("+(lb&0xff)+","+((lb>>>8)&0xff)+","+((lb>>>16)&0xff)+",a="+((lb>>>24)&0xff)+")"
                        +" dark=RGBA("+(db&0xff)+","+((db>>>8)&0xff)+","+((db>>>16)&0xff)+",a="+((db>>>24)&0xff)+")"
                        +" vtx0=("+verts.get(emStart)+","+verts.get(emStart+1)+") uv0=("+verts.get(emStart+4)+","+verts.get(emStart+5)+")"
                        +" region:"+(reg==null?"NULL":("u="+reg.getU()+" v="+reg.getV()+" u2="+reg.getU2()+" v2="+reg.getV2()+" tex="+rt)));
                }
            }
        }
        if(draws!=null){ if(n>0) draws.put((short)totalVerts); draws.flip(); }
        verts.flip();
        if (DBG && totalVerts>0){ gvNonEmptyReal++;
            if(gvNonEmptyReal%3==0){ ParticleEmitter e0=(ParticleEmitter)h.eff.emitters.get(0);
                System.err.println("[jparticle] ACTIVE id="+id+" tag='"+h.tag+"' em0=("+fp(e0,"x")+","+fp(e0,"y")+") firstVtx=("+verts.get(0)+","+verts.get(1)+")"); } }
        return n;
    }
    private static Object getPriv(Object o,String f){ try{ Field fl=findF(o.getClass(),f); fl.setAccessible(true); return fl.get(o);}catch(Exception e){ return null; } }
    private static boolean dbgQuadDone=false, dbgTintDone=false;
    private boolean emitQuad(Object p, TextureRegion region, FloatBuffer out){
        try{
            float x=fp(p,"drawX"), y=fp(p,"drawY");
            float sx=fp(p,"drawSizeX")*fp(p,"drawScaleX"), sy=fp(p,"drawSizeY")*fp(p,"drawScaleY");
            float ox=fp(p,"drawOriginX"), oy=fp(p,"drawOriginY");
            float rot=fp(p,"drawRotation");
            float light=fp(p,"drawColorPacked"), dark=fp(p,"drawTintPacked");
            if(FORCEWHITE){ light=com.badlogic.gdx.graphics.Color.WHITE.toFloatBits(); dark=0f; }
            // TEST (-Ddh.jparticle.opaque=1) : force l'alpha du light a 255 (garde le RGB) pour confirmer que
            // l'alpha 0 (transparency parsee a 0) est le tueur. Le shader two-color prend l'opacite dans light.a.
            if(OPAQUE){ int lb=Float.floatToRawIntBits(light); lb=(lb|0xff000000)&0xfeffffff; light=Float.intBitsToFloat(lb); }
            // g303 FLIPBOOK : privilegier particle.region (frame courante de l'animation, mise a jour par l'update
            // du jeu via animation.getKeyFrame) ; fallback sur la region d'emetteur (effets mono-frame).
            TextureRegion useReg=region;
            try{ Object pr=getPriv(p,"region"); if(pr instanceof TextureRegion) useReg=(TextureRegion)pr; }catch(Throwable t){}
            float u=0,v=0,u2=1,v2=1;
            if(useReg!=null){ u=useReg.getU(); v=useReg.getV(); u2=useReg.getU2(); v2=useReg.getV2(); }
            if (DBG && !dbgQuadDone) { dbgQuadDone=true;
                StringBuilder fl=new StringBuilder();
                for(Field f: p.getClass().getDeclaredFields()) fl.append(f.getName()).append('(').append(f.getType().getSimpleName()).append(") ");
                System.err.println("[jparticle] QUAD x="+x+" y="+y+" sx="+sx+" sy="+sy+" light="+light+" dark="+dark+" u="+u+" v="+v+" u2="+u2+" v2="+v2);
                System.err.println("[jparticle] Particle fields: "+fl); }
            if(DBG){ int bits=Float.floatToRawIntBits(light); int al=(bits>>>24)&0xFF; if(al>0) gvAlphaPos++; if(al>gvMaxAlpha) gvMaxAlpha=al;
                if(x<bbMinX)bbMinX=x; if(x>bbMaxX)bbMaxX=x; if(y<bbMinY)bbMinY=y; if(y>bbMaxY)bbMaxY=y; }
            // FORCEBIG : garde la POSITION réelle (x,y) mais force grosse taille + blanc opaque + rot 0.
            // Si les particules apparaissent -> le bug était la TAILLE. Sinon -> la position est hors champ.
            if(FORCEBIG){ sx=400f;sy=400f;ox=200f;oy=200f;rot=0f; light=com.badlogic.gdx.graphics.Color.WHITE.toFloatBits(); dark=0f; }
            if(sx==0f && sy==0f) return false;
            float c=(float)Math.cos(Math.toRadians(rot)), s=(float)Math.sin(Math.toRadians(rot));
            float[] lx={-ox,-ox,sx-ox,sx-ox}, ly={-oy,sy-oy,sy-oy,-oy};
            float[] uu={u,u,u2,u2}, vv={v2,v,v,v2};
            for(int i=0;i<4;i++){ float wx=x+lx[i]*c-ly[i]*s, wy=y+lx[i]*s+ly[i]*c;
                out.put(wx); out.put(wy); out.put(light); out.put(dark); out.put(uu[i]); out.put(vv[i]); }
            return true;
        }catch(Throwable t){ return false; }
    }
    private static float fp(Object o,String f){ try{ Field fl=findF(o.getClass(),f); fl.setAccessible(true); return fl.getFloat(o);}catch(Exception e){ return 0; } }
}
