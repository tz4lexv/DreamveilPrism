package dev.dreamveil.prism.pack;

import java.util.*;

/** Small metadata grammar over normal GLSL. Unknown/unsupported commands fail, never disappear. */
final class PrismMetadataParser {
    private static final List<String> COMMANDS=List.of("fullscreen","compute","scene","geometry","resource","depth","dispatch","option","history","snapshot","after","requires");
    static PrismAuthoringIR.Module parse(String path,String source) throws PrismPackLoadException {
        String[] lines=source.split("\\R",-1), visible=withoutComments(source).split("\\R",-1);
        String kind="", pass="", output="", dispatch="";
        List<String> after=new ArrayList<>(), requires=new ArrayList<>();
        List<PrismAuthoringIR.Resource> resources=new ArrayList<>();
        StringBuilder glsl=new StringBuilder();
        int conditionalDepth=0;
        for(int i=0;i<lines.length;i++) {
            String line=visible[i].trim();
            if(line.matches("#\\s*(if|ifdef|ifndef)\\b.*")) conditionalDepth++;
            if(line.matches("#\\s*endif\\b.*")) conditionalDepth--;
            if(!line.matches("#\\s*pragma\\s+prism\\b.*")) { glsl.append(lines[i]).append('\n'); continue; }
            if(conditionalDepth!=0) throw error(path,i+1,lines[i],"Conditional Prism metadata is not supported; declarations must be unconditional");
            String body=line.replaceFirst("^#\\s*pragma\\s+prism\\s*", "").trim();
            String[] parts=body.split("\\s+"); String command=parts[0];
            if(!COMMANDS.contains(command)) {
                String closest=COMMANDS.stream().min(Comparator.comparingInt(c->distance(command,c))).orElse("resource");
                throw error(path,i+1,lines[i],"Unknown pragma '"+command+"'. Did you mean '"+closest+"'? No correction was applied");
            }
            switch(command) {
                case "fullscreen", "compute" -> {
                    if(!kind.isEmpty()) throw error(path,i+1,lines[i],"One pass declaration is allowed per module");
                    if(command.equals("compute") && parts.length!=2) throw error(path,i+1,lines[i],"Use #pragma prism compute PassName");
                    if(command.equals("fullscreen") && (parts.length!=4 || !parts[2].equals("->"))) {
                        throw error(path,i+1,lines[i],"Declare the destination: #pragma prism fullscreen PassName -> scene.color");
                    }
                    kind=command; pass=symbol(parts[1],path,i+1,lines[i]);
                    if(command.equals("fullscreen")) output=parts[3];
                }
                case "dispatch" -> {
                    if(parts.length!=2 || !dispatch.isEmpty()) throw error(path,i+1,lines[i],"Use exactly one #pragma prism dispatch ResourceName");
                    dispatch=symbol(parts[1],path,i+1,lines[i]);
                }
                case "after", "requires" -> {
                    if(parts.length<2) throw error(path,i+1,lines[i],"Use #pragma prism "+command+" Name [Name ...]");
                    List<String> target=command.equals("after")?after:requires;
                    for(int p=1;p<parts.length;p++) {
                        String value=symbol(parts[p],path,i+1,lines[i]);
                        if(target.contains(value)) throw error(path,i+1,lines[i],"Duplicate "+command+" declaration: "+value);
                        target.add(value);
                    }
                }
                case "resource" -> {
                    if(parts.length<3) throw error(path,i+1,lines[i],"Use #pragma prism resource Name [format] extent transient [filter=nearest wrap=clamp]");
                    String name=symbol(parts[1],path,i+1,lines[i]);
                    Map<String,String> values=new LinkedHashMap<>();
                    if(parts.length==3 && parts[2].equals("{")) {
                        boolean closed=false;
                        glsl.append('\n');
                        while(++i<lines.length) {
                            String property=visible[i].trim(); glsl.append('\n');
                            if(property.equals("}")) { closed=true; break; }
                            if(property.isEmpty()) continue;
                            String[] pair=property.split("\\s*=\\s*",2);
                            if(pair.length!=2) throw error(path,i+1,lines[i],"Use format = r16f, size = half, lifetime = transient, filter = nearest or wrap = clamp");
                            put(values,pair[0].trim(),pair[1].replaceFirst(";$", "").trim(),path,i+1,lines[i]);
                        }
                        if(!closed) throw error(path,lines.length,"", "Unclosed resource declaration");
                    } else {
                        for(int p=2;p<parts.length;p++) {
                            String token=parts[p];
                            if(token.contains("=")) { String[] pair=token.split("=",2); put(values,pair[0],pair[1],path,i+1,lines[i]); }
                            else if(token.equals("transient") || token.equals("history=2")) put(values,"lifetime",token,path,i+1,lines[i]);
                            else if(isExtent(token)) put(values,"size",token,path,i+1,lines[i]);
                            else put(values,"format",token,path,i+1,lines[i]);
                        }
                    }
                    if(!values.containsKey("size")) throw error(path,i+1,lines[Math.min(i,lines.length-1)],"Resource '"+name+"' needs an extent; declare screen, half, quarter, scale(x), or WxH. Prism will not choose a resolution");
                    if(!values.containsKey("lifetime")) throw error(path,i+1,lines[Math.min(i,lines.length-1)],"Declare transient explicitly; history authoring is not yet available on this frontend");
                    if(!values.get("lifetime").equals("transient")) throw error(path,i+1,lines[i],"This frontend currently lowers transient resources only; use the existing manifest history contract");
                    resources.add(new PrismAuthoringIR.Resource(name,values.getOrDefault("format",""),values.get("size"),values.get("lifetime"),values.getOrDefault("filter",""),values.getOrDefault("wrap","")));
                }
                default -> throw error(path,i+1,lines[i],"Pragma '"+command+"' is reserved but not executable in this frontend milestone; use the existing manifest contract where supported");
            }
            glsl.append('\n');
        }
        if(kind.isEmpty()) throw error(path,1,lines[0],"Module has no explicit pass declaration; a filename or standalone fragment does not select a pass type");
        if(kind.equals("compute") && dispatch.isEmpty()) throw error(path,1,lines[0],"Compute requires #pragma prism dispatch ResourceName; writeonly does not choose a dispatch target");
        if(kind.equals("fullscreen") && !dispatch.isEmpty()) throw error(path,1,lines[0],"dispatch belongs to an explicitly declared compute pass");
        return new PrismAuthoringIR.Module(path,glsl.toString(),kind,pass,output,dispatch,after,requires,resources);
    }
    private static void put(Map<String,String> values,String key,String value,String path,int line,String text) throws PrismPackLoadException {
        if(!Set.of("format","size","lifetime","filter","wrap").contains(key) || value.isBlank()) throw error(path,line,text,"Unknown or empty resource field '"+key+"'");
        if(values.putIfAbsent(key,value)!=null) throw error(path,line,text,"Duplicate resource field '"+key+"'");
    }
    static boolean isExtent(String value) { return Set.of("screen","half","quarter").contains(value) || value.matches("scale\\([^()]+\\)") || value.matches("[0-9]+x[0-9]+"); }
    private static String symbol(String s,String path,int line,String text) throws PrismPackLoadException {
        if(!s.matches("[A-Za-z_][A-Za-z0-9_]{0,63}")) throw error(path,line,text,"Expected an identifier with letters, digits or underscore"); return s;
    }
    static PrismPackLoadException error(String path,int line,String text,String message) {
        return new PrismPackLoadException("PRISM_E1001", message+"\n"+text+"\n"+" ".repeat(Math.max(0,text.indexOf('#')))+"^",path+":"+line+":1");
    }
    static String withoutComments(String source) {
        StringBuilder out=new StringBuilder(source); boolean block=false, quote=false, line=false;
        for(int i=0;i<source.length();i++) {
            char c=source.charAt(i), n=i+1<source.length()?source.charAt(i+1):0;
            if(c=='\n' || c=='\r') { line=false; continue; }
            if(line) { out.setCharAt(i,' '); continue; }
            if(block) { out.setCharAt(i,' '); if(c=='*' && n=='/') { out.setCharAt(++i,' '); block=false; } continue; }
            if(c=='"' && (i==0 || source.charAt(i-1)!='\\')) quote=!quote;
            if(!quote && c=='/' && (n=='/' || n=='*')) { out.setCharAt(i,' '); out.setCharAt(++i,' '); line=n=='/'; block=n=='*'; }
        }
        return out.toString();
    }
    private static int distance(String a,String b) {
        int[] previous=new int[b.length()+1]; for(int j=0;j<previous.length;j++) previous[j]=j;
        for(int i=1;i<=a.length();i++) { int[] next=new int[b.length()+1]; next[0]=i;
            for(int j=1;j<=b.length();j++) next[j]=Math.min(Math.min(next[j-1]+1,previous[j]+1),previous[j-1]+(a.charAt(i-1)==b.charAt(j-1)?0:1)); previous=next; }
        return previous[b.length()];
    }
}
