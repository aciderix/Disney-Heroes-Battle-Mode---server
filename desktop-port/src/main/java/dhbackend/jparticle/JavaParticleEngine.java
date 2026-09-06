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
    }
    private static volatile AtlasResolver RESOLVER;
    public static void setResolver(AtlasResolver r){ RESOLVER = r; }

    private static final Map<Integer, Handle> H = new HashMap<>();
    private static int nextId = 1;

    static final class Effect { final Array<ParticleEmitter> emitters = new Array<>(); }
    static final class Handle {
        final Effect eff = new Effect();
        int atlasHandle;
        float x, y, rot;
        TextureRegion region;
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
    private void rRanged(RangedNumericValue r){ boolean a=bl(); float lo=f32(),hi=f32(); boolean lk=bl(); r.setActive(a); r.setLow(lo,hi); setPriv(r,"lowUsesLinkedRange",lk); }
    private void rScaled(ScaledNumericValue s){ rRanged(s); float hmn=f32(),hmx=f32(); boolean hk=bl(),rel=bl(); s.setHigh(hmn,hmx); setPriv(s,"highUsesLinkedRange",hk); s.setRelative(rel); int n=i32(),oa=i32(),ob=i32(); tls.add(new Tl(s,oa,ob,n)); }
    private void rNumeric(NumericValue nv){ boolean a=bl(); float v=f32(); nv.setActive(a); setPriv(nv,"value",v); }
    private void rGrad(GradientColorValue g){ bl(); i32(); i32(); i32(); }
    private void rSpawn(SpawnShapeValue sv){ boolean a=bl(); int c=b[pos++]&0xff; sv.setActive(a);
        SpawnShape sh=c==0?SpawnShape.point:c==1?SpawnShape.line:c==2?SpawnShape.square:SpawnShape.ellipse; setPriv(sv,"shape",sh);
        if(c==3){ setPriv(sv,"edges",bl()); Object[] sd=SpawnEllipseSide.class.getEnumConstants(); int si=b[pos++]&0xff; setPriv(sv,"side",sd[si%sd.length]); } }
    private ParticleEmitter readEmitter(){
        tls=new ArrayList<>(); ParticleEmitter em=new ParticleEmitter();
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
        Handle h=new Handle(); h.atlasHandle=atlasHandle;
        AtlasResolver r=RESOLVER;
        for(int i=0;i<nE;i++){ ParticleEmitter em=readEmitter();
            if(r!=null){
                BaseSprite sprite=r.spriteFor(atlasHandle, lastTag);
                if(sprite!=null){ try{ em.setSprite(sprite); }catch(Throwable t){ setPriv(em,"sprite",sprite); } }
                if(h.region==null) h.region=r.regionFor(atlasHandle, lastTag);
            }
            h.eff.emitters.add(em); }
        int id=nextId++; H.put(id,h); return id;
    }
    public synchronized void start(int id){ Handle h=H.get(id); if(h==null) return; for(ParticleEmitter e:h.eff.emitters){ e.setPosition(h.x,h.y); e.start(); } }
    public synchronized boolean update(int id, float dt){ Handle h=H.get(id); if(h==null) return false; boolean any=false; for(ParticleEmitter e:h.eff.emitters){ e.update(dt); if(e.getActiveCount()>0) any=true; } return any; }
    public synchronized void setPosition(int id,float x,float y){ Handle h=H.get(id); if(h==null) return; h.x=x; h.y=y; for(ParticleEmitter e:h.eff.emitters) e.setPosition(x,y); }
    public synchronized void setRotation(int id,float r){ Handle h=H.get(id); if(h==null) return; h.rot=r; }
    public synchronized void dispose(int id){ H.remove(id); }
    public synchronized int activeCount(int id){ Handle h=H.get(id); if(h==null) return 0; int n=0; for(ParticleEmitter e:h.eff.emitters) n+=e.getActiveCount(); return n; }

    // Remplit verts (6 floats/sommet) + draws (n*3+1 shorts) ; retourne n (draw calls).
    public synchronized int getVertices(int id, FloatBuffer verts, ShortBuffer draws){
        Handle h=H.get(id); if(h==null) return 0;
        verts.clear(); int vcount=0;
        for(ParticleEmitter em:h.eff.emitters){
            Object parts=getPriv(em,"particles"); if(!(parts instanceof Object[])) continue;
            for(Object p:(Object[])parts){ if(p==null) continue; if(emitQuad(p,h.region,verts)) vcount+=4; }
        }
        int n = vcount>0 ? 1 : 0;
        if(draws!=null){ draws.clear(); if(n>0){ draws.put((short)0); draws.put((short)0); draws.put((short)vcount); draws.put((short)vcount); } draws.flip(); }
        verts.flip();
        return n;
    }
    private static Object getPriv(Object o,String f){ try{ Field fl=findF(o.getClass(),f); fl.setAccessible(true); return fl.get(o);}catch(Exception e){ return null; } }
    private boolean emitQuad(Object p, TextureRegion region, FloatBuffer out){
        try{
            float x=fp(p,"drawX"), y=fp(p,"drawY");
            float sx=fp(p,"drawSizeX")*fp(p,"drawScaleX"), sy=fp(p,"drawSizeY")*fp(p,"drawScaleY");
            float ox=fp(p,"drawOriginX"), oy=fp(p,"drawOriginY");
            float rot=fp(p,"drawRotation");
            float light=fp(p,"drawColorPacked"), dark=fp(p,"drawTintPacked");
            float u=0,v=0,u2=1,v2=1;
            if(region!=null){ u=region.getU(); v=region.getV(); u2=region.getU2(); v2=region.getV2(); }
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
