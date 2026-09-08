package com.starlwr.bot.report.painter;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.TreeMap;
import java.util.Map;

/** 示例版：主词错落锚点 + 全画布候选评分 + 横纵分离间距。
 * 不依赖词语内容；相同输入、字体、种子得到相同布局。
 * fillRatio 保留接口兼容，仅作包围盒统计，不再作为字号放大目标。
 */
final class WordCloudLayout {
    static final int WORD_GAP = 7;
    static final int FRAME_MARGIN = 24;
    record Word(String text, int count) {}
    record Placement(String text, int rank, int fontSize, Rectangle box, Color color) {}
    record Result(List<Placement> placements, Rectangle bounds, double fillRatio, int dropped) {}
    interface Measurer { Dimension measure(String text, int fontSize); }
    private static final Color[] PALETTE = {
        new Color(0xE0479E), new Color(0x7A4DFF), new Color(0xA38BFF),
        new Color(0x00A6D6), new Color(0x6E6A86), new Color(0xF2A93B)
    };
    // 相对锚点只随名次生效，不硬编码任何词。
    private static final double[][] ANCHORS = {
        {.50,.49}, {.70,.29}, {.30,.66}, {.32,.27}, {.76,.63},
        {.51,.79}, {.18,.42}, {.80,.43}, {.51,.17}, {.17,.72}
    };
    private WordCloudLayout() {}
    /** 报告调用层须同时把此高度交给 layout 与 render，并按实际图高安排后续模块。 */
    static int recommendedHeight(int validWordCount,int maxHeight) {
        int preferred=validWordCount==0?96:validWordCount<=3?128:validWordCount<=8?200:
            validWordCount<=12?240:validWordCount<=24?300:380;
        return Math.min(maxHeight,preferred);
    }
    static Result layout(List<Word> words, int width, int height, long seed, Measurer measurer) {
        Map<String,Integer> merged=new TreeMap<>();
        if(words!=null) for(Word w:words) {
            if(w==null || w.text()==null || w.text().isBlank() || w.count()<=0) continue;
            String text=w.text().strip();
            merged.merge(text,w.count(),(a,b)->(int)Math.min(Integer.MAX_VALUE,(long)a+b));
        }
        List<Word> sorted = new ArrayList<>();
        merged.forEach((text,count)->sorted.add(new Word(text,count)));
        sorted.sort(Comparator.comparingInt(Word::count).reversed().thenComparing(Word::text));
        if (sorted.isEmpty() || width <= 2*FRAME_MARGIN || height <= 2*FRAME_MARGIN)
            return new Result(List.of(), new Rectangle(), 0, sorted.size());
        if(sorted.size()<=12) return sparse(sorted,width,height,seed,measurer);
        // 中等词量使用较小排版区域，避免只有十几个词却撑开整张画布。
        if(sorted.size()<40) {
            double factor=.70+.30*(sorted.size()-13)/27.0;
            int innerW=(int)(width*factor),innerH=(int)(height*(.80+.20*(sorted.size()-13)/27.0));
            return offset(dense(sorted,innerW,innerH,seed,measurer),(width-innerW)/2,(height-innerH)/2,width,height);
        }
        return dense(sorted,width,height,seed,measurer);
    }
    private static Result dense(List<Word> sorted,int width,int height,long seed,Measurer measurer) {
        // 尝试较小整体字号时优先保词；不靠放大外包围盒追求“填满”。
        Result best = null;
        for (double scale : new double[]{1.0, .94, .88, .78, .68, .58, .48}) {
            Result r = place(sorted,width,height,seed,measurer,scale);
            if (best == null || r.placements().size() > best.placements().size()) best=r;
            if (r.dropped()==0) break;
        }
        return best;
    }
    /** 过长整句缩略展示，原始文字和词频仍保留在输入数据中。 */
    private static String displayLabel(String text) {
        int[] cp=text.codePoints().toArray();
        return cp.length<=12?text:new String(cp,0,11)+"…";
    }
    private static Result offset(Result r,int dx,int dy,int width,int height) {
        List<Placement> list=new ArrayList<>();
        for(Placement p:r.placements()) {Rectangle box=new Rectangle(p.box());box.translate(dx,dy);
            list.add(new Placement(p.text(),p.rank(),p.fontSize(),box,p.color()));}
        Rectangle b=new Rectangle(r.bounds());if(!b.isEmpty()) b.translate(dx,dy);
        return new Result(List.copyOf(list),b,(double)b.width*b.height/(width*(double)height),r.dropped());
    }
    /** 少词时用紧凑、居中的短行词团；不为填满面积强行放大或散开。 */
    private static Result sparse(List<Word> words,int width,int height,long seed,Measurer measurer) {
        Result best=null;
        for(double scale:new double[]{1,.88,.76,.64}) {
            List<Placement> list=new ArrayList<>();
            List<List<Placement>> rows=new ArrayList<>();List<Placement> row=new ArrayList<>();
            int max=words.get(0).count(),min=words.get(words.size()-1).count();
            int rowLimit=Math.min(width-2*FRAME_MARGIN,words.size()<=3?560:words.size()<=8?360:480);
            int used=0,previousSize=44;
            for(int i=0;i<words.size();i++) {
                double q=max==min?.25:(double)(words.get(i).count()-min)/(max-min);
                int size=Math.max(18,(int)Math.round((words.size()==1?40:28+16*Math.sqrt(q))*scale));
                if(i==0 && words.size()>1) {
                    double q2=max==min?.25:(double)(words.get(1).count()-min)/(max-min);
                    size=Math.min(size,(int)(Math.max(18,Math.round((28+16*Math.sqrt(q2))*scale))*1.3));
                }
                size=Math.min(size,previousSize);previousSize=size;
                String label=displayLabel(words.get(i).text());Dimension d=measurer.measure(label,size);
                while(d.width>rowLimit && size>18) d=measurer.measure(label,--size);
                if(d.width<=0 || d.height<=0 || d.width>rowLimit) continue;
                if(!row.isEmpty() && used+22+d.width>rowLimit) {rows.add(row);row=new ArrayList<>();used=0;}
                row.add(new Placement(label,i+1,size,new Rectangle(0,0,d.width,d.height),color(i,seed)));
                used+=d.width+(row.size()>1?22:0);
            }
            if(!row.isEmpty())rows.add(row);
            int totalHeight=0;
            for(List<Placement> rr:rows)totalHeight+=rr.stream().mapToInt(p->p.box().height).max().orElse(0)+18;
            totalHeight=Math.max(0,totalHeight-18);
            int y=(height-totalHeight)/2;
            if(y<FRAME_MARGIN) continue;
            Rectangle bounds=new Rectangle();
            for(List<Placement> rr:rows) {
                int rw=rr.stream().mapToInt(p->p.box().width).sum()+22*(rr.size()-1);
                int rh=rr.stream().mapToInt(p->p.box().height).max().orElse(0);
                int x=(width-rw)/2;
                for(Placement p:rr) {
                    Rectangle b=new Rectangle(x,y+(rh-p.box().height)/2,p.box().width,p.box().height);
                    list.add(new Placement(p.text(),p.rank(),p.fontSize(),b,p.color()));
                    bounds=bounds.isEmpty()?new Rectangle(b):bounds.union(b);x+=b.width+22;
                }
                y+=rh+18;
            }
            Result r=new Result(List.copyOf(list),bounds,(double)bounds.width*bounds.height/(width*(double)height),words.size()-list.size());
            if(best==null || r.dropped()<best.dropped())best=r;
            if(r.dropped()==0) break;
        }
        return best==null?new Result(List.of(),new Rectangle(),0,words.size()):best;
    }
    private static Result place(List<Word> words,int width,int height,long seed,Measurer measurer,double scale) {
        List<Placement> placed = new ArrayList<>();
        Random random = new Random(seed);
        int max=words.get(0).count(), min=words.get(words.size()-1).count();
        int[] sizes=new int[words.size()];
        for(int i=0;i<sizes.length;i++) {
            double q=max==min?0:(double)(words.get(i).count()-min)/(max-min);
            sizes[i]=Math.max(18,(int)Math.round((q==0 && max!=min ? 18 : 21+35*Math.pow(q,.60))*scale));
        }
        if(sizes.length>1) sizes[0]=Math.min(sizes[0],(int)(sizes[1]*1.3));
        for(int i=0;i<words.size();i++) {
            int size=sizes[i];
            String label=displayLabel(words.get(i).text());
            Dimension d=measurer.measure(label,size);
            while(d.width>width-2*FRAME_MARGIN && size>18) d=measurer.measure(label,--size);
            if(d.width<=0 || d.height<=0) continue;
            double tx,ty;
            if(i<ANCHORS.length) {tx=width*ANCHORS[i][0];ty=height*ANCHORS[i][1];}
            else {tx=width*(.10+.80*random.nextDouble());ty=height*(.11+.78*random.nextDouble());}
            Rectangle best=null;double bestScore=Double.POSITIVE_INFINITY;
            // 遍历二维候选，避免全部词都沿同一条中心螺旋向外挤。
            int ox=random.nextInt(4),oy=random.nextInt(4);
            int step=size<=21?2:4;
            for(int y=FRAME_MARGIN+oy;y+d.height<=height-FRAME_MARGIN;y+=step) {
                for(int x=FRAME_MARGIN+ox;x+d.width<=width-FRAME_MARGIN;x+=step) {
                    Rectangle box=new Rectangle(x,y,d.width,d.height);
                    if(!fits(box,size,placed)) continue;
                    double cx=box.getCenterX(),cy=box.getCenterY();
                    // 超椭圆的软边界：利用四角，轮廓仍有收边。
                    double edge=Math.pow(Math.abs((cx-width*.5)/(width*.46)),4)
                        +Math.pow(Math.abs((cy-height*.5)/(height*.45)),4);
                    double target=Math.pow((cx-tx)/width,2)+Math.pow((cy-ty)/height,2);
                    double score=target*(i<10?12:.24)+Math.max(0,edge-.72)*.7;
                    if(i>=10) {
                        score+=.62*(Math.pow((cx-width*.5)/width,2)+Math.pow((cy-height*.5)/height,2));
                        // 惩罚孤立词，但不把小词全部拉回中心形成环带。
                        double nearest=Double.POSITIVE_INFINITY;
                        for(Placement p:placed) nearest=Math.min(nearest,boxDistance(box,p.box()));
                        score+=Math.pow(Math.max(0,nearest-18)/100,2)*.45;
                    }
                    if(score<bestScore) {bestScore=score;best=box;}
                }
            }
            if(best!=null) placed.add(new Placement(label,i+1,size,best,color(i,seed)));
        }
        if(words.size()>=40) refineInterior(placed,width,height);
        Rectangle bounds=new Rectangle();
        for(Placement p:placed) bounds=bounds.isEmpty()?new Rectangle(p.box()):bounds.union(p.box());
        return new Result(List.copyOf(placed),bounds,(double)bounds.width*bounds.height/(width*(double)height),words.size()-placed.size());
    }
    /** 锁定主词和外缘，仅在原位置附近微调中小词，减少内部大空洞。 */
    private static void refineInterior(List<Placement> placed,int width,int height) {
        List<Rectangle> originals=new ArrayList<>();
        for(Placement p:placed) originals.add(new Rectangle(p.box()));
        List<double[]> probes=new ArrayList<>();
        for(int y=50;y<height-50;y+=7) for(int x=90;x<width-90;x+=7) {
            double nx=(x-width*.5)/(width*.38), ny=(y-height*.5)/(height*.38);
            if(nx*nx+ny*ny<1) probes.add(new double[]{x,y});
        }
        for(int pass=0;pass<3;pass++) {
            boolean changed=false;
            for(int i=placed.size()-1;i>=10;i--) {
                Placement old=placed.get(i); Rectangle origin=originals.get(i);
                // 外围位置完全锁定，保证已认可的轮廓。
                if(origin.x<105 || origin.getMaxX()>width-105 || origin.y<70 || origin.getMaxY()>height-75) continue;
                List<Placement> others=new ArrayList<>(placed);others.remove(i);
                double[] nearest=new double[probes.size()];
                for(int j=0;j<probes.size();j++) {
                    nearest[j]=Double.POSITIVE_INFINITY;
                    for(Placement q:others) nearest[j]=Math.min(nearest[j],pointDistance(probes.get(j),q.box()));
                }
                Rectangle best=old.box();double bestScore=gapScore(best,origin,probes,nearest);
                for(int dy=-18;dy<=18;dy+=3) for(int dx=-18;dx<=18;dx+=3) {
                    Rectangle box=new Rectangle(origin.x+dx,origin.y+dy,origin.width,origin.height);
                    if(!refinementFits(box,old.fontSize(),others)) continue;
                    double score=gapScore(box,origin,probes,nearest);
                    if(score<bestScore-.01) {best=box;bestScore=score;}
                }
                if(!best.equals(old.box())) {
                    placed.set(i,new Placement(old.text(),old.rank(),old.fontSize(),best,old.color()));changed=true;
                }
            }
            if(!changed) break;
        }
    }
    private static double pointDistance(double[] point,Rectangle box) {
        return Math.hypot(Math.max(0,Math.max(box.x-point[0],point[0]-box.getMaxX())),
            Math.max(0,Math.max(box.y-point[1],point[1]-box.getMaxY())));
    }
    private static double gapScore(Rectangle box,Rectangle origin,List<double[]> probes,double[] nearest) {
        double score=.15*(Math.pow(box.x-origin.x,2)+Math.pow(box.y-origin.y,2));
        for(int j=0;j<nearest.length;j++) {
            double d=Math.min(nearest[j],pointDistance(probes.get(j),box));
            score+=Math.pow(Math.max(0,d-8),2);
        }
        return score;
    }
    private static boolean refinementFits(Rectangle box,int size,List<Placement> others) {
        for(Placement p:others) {
            double fs=Math.sqrt(size*(double)p.fontSize());
            int gx=Math.max(6,(int)Math.round(fs*.22));
            int gy=Math.max(5,(int)Math.round(fs*.14));
            if(p.box().intersects(new Rectangle(box.x-gx,box.y-gy,box.width+2*gx,box.height+2*gy))) return false;
        }
        return true;
    }
    private static double boxDistance(Rectangle a,Rectangle b) {
        double dx=Math.max(0,Math.max(a.x-b.getMaxX(),b.x-a.getMaxX()));
        double dy=Math.max(0,Math.max(a.y-b.getMaxY(),b.y-a.getMaxY()));
        return Math.hypot(dx,dy);
    }
    private static boolean fits(Rectangle a,int size,List<Placement> placed) {
        for(Placement p:placed) {
            // 大小词配对使用几何平均字号，避免小词也被大词的宽间距排斥。
            double fs=Math.sqrt(size*(double)p.fontSize());
            boolean small=Math.min(size,p.fontSize())<=21;
            int gx=Math.max(small?6:WORD_GAP,(int)Math.round(fs*(small?.21:.26)));
            int gy=Math.max(small?5:6,(int)Math.round(fs*(small?.14:.17)));
            if(p.box().intersects(new Rectangle(a.x-gx,a.y-gy,a.width+2*gx,a.height+2*gy))) return false;
        }
        return true;
    }
    private static Color color(int index,long seed) {
        if(index<3) return PALETTE[0];
        if(index<10) return PALETTE[1];
        // 六种在册颜色保持不变；中尾词交错分配，橙色缩减为约每12词一个。
        int slot=Math.floorMod(index+(int)(seed%12),12);
        if(slot==0) return PALETTE[5];
        if(slot==3 || slot==8) return PALETTE[3];
        if(slot==5 || slot==10) return PALETTE[2];
        return PALETTE[4];
    }
}
