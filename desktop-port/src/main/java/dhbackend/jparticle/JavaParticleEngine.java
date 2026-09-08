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
        rScaled(em.getGravity()); rScaled(em.getTransparency());
        ScaledNumericValue d1=new ScaledNumericValue(); rScaled(d1);
        rRanged(em.getCentripetalRadiusValue()); rScaled(em.getCentripetalForceValue()); rScaled(em.getTangentialForceValue()); rRanged(em.getTangentialRadiusValue());
        ScaledNumericValue d2=new ScaledNumericValue(); rScaled(d2);
        rGrad(em.getTint()); ScaledNumericValue d3=new ScaledNumericValue(); rScaled(d3);
        setPriv(em,"frameDuration",f32());
        boolean att=bl(),cont=bl(),ali=bl(); int fl=b[pos++]&0xff; boolean beh=bl();
        setPriv(em,"attached",att); setPriv(em,"continuous",cont); setPriv(em,"aligned",ali); setPriv(em,"additive",(fl&1)!=0); setPriv(em,"behind",beh);
        int poolSize=i32(),tagLen=i32(); float[] pool=new float[poolSize]; for(int i=0;i<poolSize;i++) pool[i]=f32();
        lastTag = tagLen>0 ? new String(b, pos, tagLen, java.nio.charset.StandardCharsets.UTF_8) : "";
        pos+=tagLen;
        for(Tl t:tls){ if(t.n>0 && t.oa+t.n<=poolSize && t.ob+t.n<=poolSize){ t.s.setTimeline(Arrays.copyOfRange(pool,t.oa,t.oa+t.n)); t.s.setScaling(Arrays.copyOfRange(pool,t.ob,t.ob+t.n)); } }
        // tint : n points -> couleurs = 3n floats RGB @colorsOff, timeline = n floats @timelineOff
        for(Object[] g:gts){ GradientColorValue gc=(GradientColorValue)g[0]; int n=(int)g[1],oa=(int)g[2],ob=(int)g[3];
            if(n>0 && oa+3*n<=poolSize && ob+n<=poolSize){ float[] cols=Arrays.copyOfRange(pool,oa,oa+3*n), tml=Arrays.copyOfRange(pool,ob,ob+n);
                gc.setColors(cols); setPriv(gc,"timeline",tml);
                if(DBG && !dbgTintDone){ dbgTintDone=true; System.err.println("[jparticle] TINT n="+n+" colors="+Arrays.toString(cols)+" timeline="+Arrays.toString(tml)); } } }
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
    private static int gvCalls=0, gvNonEmpty=0, gvLastLog=-1;
    public synchronized void start(int id){ Handle h=H.get(id); if(h==null) return; for(ParticleEmitter e:h.eff.emitters){ e.setPosition(h.x,h.y); e.start(); } }
    public synchronized boolean update(int id, float dt){ Handle h=H.get(id); if(h==null) return false; boolean any=false, allComplete=true;
        for(ParticleEmitter e:h.eff.emitters){ e.update(dt); if(e.getActiveCount()>0) any=true; if(!e.isComplete()) allComplete=false; }
        if(h.disposed && allComplete && !any){ H.remove(id); if(DBG) System.err.println("[jparticle] cleanup id="+id+" (disposed+complete)"); }
        return any; }
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

    // Remplit verts (6 floats/sommet) + draws (n*3+1 shorts) ; retourne n (draw calls).
    private static final boolean TESTQUAD = "1".equals(System.getProperty("dh.jparticle.testquad"));
    private static final boolean FORCEWHITE = "1".equals(System.getProperty("dh.jparticle.forcewhite"));
    private static final boolean CALIB = "1".equals(System.getProperty("dh.jparticle.calib"));
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
            int emVerts=0;
            for(Object p:(Object[])parts){ if(p==null) continue; if(emitQuad(p,reg,verts)) emVerts+=4; }
            if(emVerts>0){
                boolean additive = Boolean.TRUE.equals(getPriv(em,"additive"));
                if(draws!=null){ draws.put((short)(emVerts/4*6)); draws.put((short)(additive?3:0)); draws.put((short)page); }
                totalVerts+=emVerts; n++;
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
            float u=0,v=0,u2=1,v2=1;
            if(region!=null){ u=region.getU(); v=region.getV(); u2=region.getU2(); v2=region.getV2(); }
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
