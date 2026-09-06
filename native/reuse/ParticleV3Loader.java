import com.badlogic.gdx.graphics.g2d.ParticleEmitter;
import com.badlogic.gdx.graphics.g2d.ParticleEmitter.*;
import com.badlogic.gdx.graphics.g2d.BaseSprite;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Texture;
import java.io.*; import java.nio.file.*; import java.lang.reflect.*; import java.util.*;

public class ParticleV3Loader {
    final byte[] b; int pos;
    ParticleV3Loader(byte[] b){this.b=b;}
    int i32(){ int v=((b[pos]&0xff)<<24)|((b[pos+1]&0xff)<<16)|((b[pos+2]&0xff)<<8)|(b[pos+3]&0xff); pos+=4; return v; }
    float f32(){ return Float.intBitsToFloat(i32()); }
    boolean bl(){ return b[pos++]!=0; }
    static void setPriv(Object o,String f,Object v){ try{Field fl=findF(o.getClass(),f);fl.setAccessible(true);fl.set(o,v);}catch(Exception e){throw new RuntimeException(f+":"+e);} }
    static Field findF(Class<?> c,String f){ for(;c!=null;c=c.getSuperclass()){try{return c.getDeclaredField(f);}catch(Exception e){}} throw new RuntimeException("no "+f); }
    static class Tl { ScaledNumericValue s; int offA,offB,n; Tl(ScaledNumericValue s,int a,int bb,int n){this.s=s;offA=a;offB=bb;this.n=n;} }
    List<Tl> tls = new ArrayList<>();
    void rdRanged(RangedNumericValue r){ boolean a=bl(); float lo=f32(),hi=f32(); boolean lk=bl(); r.setActive(a); r.setLow(lo,hi); setPriv(r,"lowUsesLinkedRange",lk); }
    void rdScaled(ScaledNumericValue s){ rdRanged(s); float hmn=f32(),hmx=f32(); boolean hk=bl(),rel=bl(); s.setHigh(hmn,hmx); setPriv(s,"highUsesLinkedRange",hk); s.setRelative(rel); int n=i32(),oa=i32(),ob=i32(); tls.add(new Tl(s,oa,ob,n)); }
    void rdNumeric(NumericValue nv){ boolean a=bl(); float v=f32(); nv.setActive(a); setPriv(nv,"value",v); }
    void rdGradient(GradientColorValue g){ bl(); i32(); i32(); i32(); }
    void rdSpawn(SpawnShapeValue sv){ boolean a=bl(); int code=b[pos++]&0xff; sv.setActive(a);
        SpawnShape sh=code==0?SpawnShape.point:code==1?SpawnShape.line:code==2?SpawnShape.square:SpawnShape.ellipse; setPriv(sv,"shape",sh);
        if(code==3){ setPriv(sv,"edges",bl()); Object[] sides=SpawnEllipseSide.class.getEnumConstants(); int si=b[pos++]&0xff; setPriv(sv,"side",sides[si%sides.length]); } }
    static class MockSprite implements BaseSprite {
        final Color c=new Color(1,1,1,1);
        public Color getColor(){return c;} public float getWidth(){return 32;} public float getHeight(){return 32;}
        public float getOriginX(){return 16;} public float getOriginY(){return 16;} public Texture getTexture(){return null;}
    }
    ParticleEmitter readEmitter(){
        tls.clear(); ParticleEmitter em=new ParticleEmitter();
        int min=i32(),max=i32(); em.setMinParticleCount(min); em.setMaxParticleCount(max);
        rdRanged(em.getDelay()); rdRanged(em.getDuration());
        rdScaled(em.getEmission()); rdScaled(em.getLife()); rdScaled(em.getLifeOffset());
        rdScaled(em.getTangentialInfluenceValue()); rdScaled(em.getCentripetalInfluenceValue()); rdScaled(em.getBrownianValue());
        rdNumeric(em.getZToYMultiplierValue()); rdSpawn(em.getSpawnShape());
        rdScaled(em.getSpawnWidth()); rdScaled(em.getSpawnHeight()); rdScaled(em.getSizeX()); rdScaled(em.getSizeY());
        rdScaled(em.getVelocity()); rdScaled(em.getVelocityZ()); rdScaled(em.getAngle()); rdScaled(em.getRotation()); rdScaled(em.getWind());
        rdScaled(em.getGravity()); rdScaled(em.getTransparency());
        ScaledNumericValue d1=new ScaledNumericValue(); rdScaled(d1);
        rdRanged(em.getCentripetalRadiusValue()); rdScaled(em.getCentripetalForceValue()); rdScaled(em.getTangentialForceValue()); rdRanged(em.getTangentialRadiusValue());
        ScaledNumericValue d2=new ScaledNumericValue(); rdScaled(d2);
        rdGradient(em.getTint()); ScaledNumericValue d3=new ScaledNumericValue(); rdScaled(d3);
        setPriv(em,"frameDuration",f32());
        boolean att=bl(),cont=bl(),ali=bl(); int flags=b[pos++]&0xff; boolean beh=bl();
        setPriv(em,"attached",att); setPriv(em,"continuous",cont); setPriv(em,"aligned",ali); setPriv(em,"additive",(flags&1)!=0); setPriv(em,"behind",beh);
        int poolSize=i32(),tagLen=i32(); float[] pool=new float[poolSize]; for(int i=0;i<poolSize;i++) pool[i]=f32(); pos+=tagLen;
        for(Tl t:tls){ if(t.n>0 && t.offA+t.n<=poolSize && t.offB+t.n<=poolSize){ t.s.setTimeline(Arrays.copyOfRange(pool,t.offA,t.offA+t.n)); t.s.setScaling(Arrays.copyOfRange(pool,t.offB,t.offB+t.n)); } }
        return em;
    }
    public static void main(String[] a) throws Exception {
        byte[] np=Files.readAllBytes(Paths.get(a[0])); ParticleV3Loader L=new ParticleV3Loader(np);
        L.pos=2; int nE=L.i32(); System.out.println("emitters="+nE);
        ParticleEmitter em=L.readEmitter();
        System.out.printf("emit0 : min=%d max=%d vel=%.0f angle=%.0f sizeX=%.0f wind=%.0f emissionHi=%.0f lifeHi=%.0f%n",
            em.getMinParticleCount(),em.getMaxParticleCount(),em.getVelocity().getHighMax(),em.getAngle().getHighMax(),
            em.getSizeX().getHighMax(),em.getWind().getHighMax(),em.getEmission().getHighMax(),em.getLife().getHighMax());
        com.badlogic.gdx.graphics.g2d.TextureRegion tr=new com.badlogic.gdx.graphics.g2d.TextureRegion(); setPriv(tr,"regionWidth",32); setPriv(tr,"regionHeight",32); setPriv(em,"sprite",tr); em.setPosition(0,0); em.start();
        Field pf=findF(ParticleEmitter.class,"particles"); pf.setAccessible(true);
        for(int f=0;f<6;f++){ em.update(0.1f); Object[] parts=(Object[])pf.get(em); StringBuilder sb=new StringBuilder(); int sh=0;
            for(Object pp:parts){ if(pp==null) continue; Field xf=findF(pp.getClass(),"x"),yf=findF(pp.getClass(),"y"); xf.setAccessible(true); yf.setAccessible(true);
                sb.append(String.format("(%.1f,%.1f) ",xf.getFloat(pp),yf.getFloat(pp))); if(++sh>=4) break; }
            System.out.printf("  frame %d : active=%d pos=%s%n",f,em.getActiveCount(),sb); }
        System.out.println("OK -- le moteur du jeu SIMULE (spawn+mouvement) via son propre update() !");
    }
}
