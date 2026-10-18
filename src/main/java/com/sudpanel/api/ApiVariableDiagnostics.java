package com.sudpanel.api;

import java.util.*;
import java.util.regex.Pattern;

/** 占位符定位只返回名称、位置、作用域及候选名，绝不返回变量值。 */
public final class ApiVariableDiagnostics {
    private ApiVariableDiagnostics() {}
    private static final Pattern TOKEN=Pattern.compile("\\{\\{\\s*([^{}]+?)\\s*\\}\\}");
    public record Issue(String name,String scope,String location,int offset,int line,int column,String status,String source,List<String> candidates,List<String> shadowedSources) {}
    public static List<Issue> inspect(ApiSpec spec,ApiRequestEngine.Environment environment,Map<String,String> run,Map<String,String> data,Map<String,String> overrides) {
        var out=new ArrayList<Issue>();var vars=ApiVariables.overrideBody(environment.vars(),overrides);
        scan(spec.url(),"URL","url",spec,environment,vars,run,data,out);
        if(spec.options()==null||spec.options().headerRows()==null)scan(spec.headers(),"HEADER","headers",spec,environment,vars,run,data,out);
        else for(int i=0;i<spec.options().headerRows().size();i++){var row=spec.options().headerRows().get(i);if(row!=null&&!Boolean.FALSE.equals(row.enabled())){scan(row.name(),"HEADER","headerRows["+i+"].name",spec,environment,vars,run,data,out);scan(row.value(),"HEADER","headerRows["+i+"].value",spec,environment,vars,run,data,out);}}
        if(spec.options()==null||!List.of("NONE","URLENCODED","MULTIPART","BINARY").contains(spec.options().bodyType()==null?"LEGACY":spec.options().bodyType()))scan(spec.body(),"BODY","body",spec,environment,vars,run,data,out);
        var options=spec.options();if(options!=null){
            if(options.params()!=null)for(int i=0;i<options.params().size();i++){var p=options.params().get(i);if(p!=null&&!Boolean.FALSE.equals(p.enabled())){scan(p.name(),"URL","params["+i+"].name",spec,environment,vars,run,data,out);scan(p.value(),"URL","params["+i+"].value",spec,environment,vars,run,data,out);}}
            if("URLENCODED".equals(options.bodyType())&&options.fields()!=null)for(int i=0;i<options.fields().size();i++){var p=options.fields().get(i);if(p!=null&&!Boolean.FALSE.equals(p.enabled())){scan(p.name(),"BODY","fields["+i+"].name",spec,environment,vars,run,data,out);scan(p.value(),"BODY","fields["+i+"].value",spec,environment,vars,run,data,out);}}
            if("MULTIPART".equals(options.bodyType())&&options.parts()!=null)for(int i=0;i<options.parts().size();i++){var p=options.parts().get(i);if(p!=null&&!Boolean.FALSE.equals(p.enabled())){scan(p.name(),"BODY","parts["+i+"].name",spec,environment,vars,run,data,out);if(!"FILE".equals(p.type()))scan(p.value(),"BODY","parts["+i+"].value",spec,environment,vars,run,data,out);}}
            if(options.auth()!=null){var a=options.auth();String scope="API_KEY".equals(a.type())&&"QUERY".equals(a.in())?"URL":"HEADER";
                if(Set.of("SCRIPT","TEMPLATE").contains(a.type()==null?"":a.type()))scan(a.authorizationTemplate(),"HEADER","auth.authorizationTemplate",spec,environment,vars,run,data,out);
                if("BEARER".equals(a.type()))scan(a.token(),scope,"auth.token",spec,environment,vars,run,data,out);if("BASIC".equals(a.type())){scan(a.username(),scope,"auth.username",spec,environment,vars,run,data,out);scan(a.password(),scope,"auth.password",spec,environment,vars,run,data,out);}if("API_KEY".equals(a.type())){scan(a.keyName(),scope,"auth.keyName",spec,environment,vars,run,data,out);scan(a.keyValue(),scope,"auth.keyValue",spec,environment,vars,run,data,out);}}
        }
        return List.copyOf(out);
    }
    private static void scan(String text,String scope,String location,ApiSpec spec,ApiRequestEngine.Environment env,ApiVariables.VarSet vars,Map<String,String> run,Map<String,String> data,List<Issue> out) {
        if(text==null)return;var matcher=TOKEN.matcher(text);
        while(matcher.find()&&out.size()<500){String name=matcher.group(1).trim(),source=null;var shadows=new ArrayList<String>();var candidates=new LinkedHashSet<String>();
            var base=ApiVariables.toMap(vars.base());var header=ApiVariables.toMap(vars.header());var body=ApiVariables.toMap(vars.body());
            boolean found=false,literal=name.startsWith("!");
            if(literal){source="LITERAL";found=true;}
            else if(name.startsWith("$")){
                source="SYSTEM";found=Set.of("$timestamp","$timestampMs","$uuid").contains(name)||name.startsWith("$date:")||name.startsWith("$randomInt:");
                String auth=spec.options()==null||spec.options().auth()==null?"INHERIT":spec.options().auth().type();boolean sud=sud(spec,env);
                if(name.startsWith("$auth."))found=sud&&Set.of("$auth.app_id","$auth.mg_id","$auth.nonce","$auth.sign").contains(name)&&!("$auth.sign".equals(name)&&scope.equals("BODY"));
                if("$auth.app_id".equals(name)&&env.sign().appId()==null)found=false;if("$auth.mg_id".equals(name)&&env.sign().mgId()==null)found=false;
                if(base.containsKey(name))shadows.add("BASE");if(header.containsKey(name))shadows.add("HEADER");if(body.containsKey(name))shadows.add("BODY");
                candidates.addAll(List.of("$timestamp","$timestampMs","$uuid","$auth.app_id","$auth.mg_id","$auth.nonce","$auth.sign"));
            } else if(name.startsWith("run.")||name.startsWith("data.")) {boolean fromData=name.startsWith("data.");source=fromData?"DATA":"RUN";var values=fromData?data:run;found=values!=null&&values.get(name.substring(fromData?5:4))!=null;if(values!=null)values.keySet().forEach(k->candidates.add((fromData?"data.":"run.")+k));}
            else {
                var values="BODY".equals(scope)?body:header;candidates.addAll(values.keySet());
                if("URL".equals(scope)){candidates.addAll(base.keySet());if(base.get(name)!=null){source="BASE";found=true;if(header.containsKey(name))shadows.add("HEADER");}}
                if(!found&&values.get(name)!=null){found=true;source="BODY".equals(scope)?"BODY":"HEADER";}
                if(!found&&sud(spec,env)&&List.of("times","nonce","app_id","mg_id","sign").contains(name)){found=true;source="LEGACY_SIGN";}
            }
            int line=1,column=1;for(int i=0;i<matcher.start();i++){if(text.charAt(i)=='\n'){line++;column=1;}else column++;}
            String lookup=name;List<String> suggested=found?List.of():candidates.stream().filter(c->distance(lookup,c)<=2||c.startsWith(lookup)||lookup.startsWith(c)).sorted(Comparator.comparingInt(c->distance(lookup,c))).limit(5).toList();
            out.add(new Issue(name,scope,location,matcher.start(),line,column,literal?"LITERAL":found?"RESOLVED":"MISSING",source,suggested,List.copyOf(shadows)));
        }
    }
    private static boolean sud(ApiSpec spec,ApiRequestEngine.Environment env){String type=spec.options()==null||spec.options().auth()==null?"INHERIT":spec.options().auth().type();return "SUD".equals(type)||((type==null||"INHERIT".equals(type))&&env.sign().enabled());}
    private static int distance(String a,String b){if(Math.abs(a.length()-b.length())>2)return 3;if(a.length()>150||b.length()>150)return 3;int[] previous=new int[b.length()+1];for(int j=0;j<previous.length;j++)previous[j]=j;for(int i=1;i<=a.length();i++){int[] current=new int[b.length()+1];current[0]=i;for(int j=1;j<=b.length();j++)current[j]=Math.min(Math.min(current[j-1]+1,previous[j]+1),previous[j-1]+(a.charAt(i-1)==b.charAt(j-1)?0:1));previous=current;}return previous[b.length()];}
}
