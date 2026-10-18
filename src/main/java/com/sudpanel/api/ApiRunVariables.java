package com.sudpanel.api;

/** Runtime values and their explicit privacy marks share one lifetime and one environment. */
public final class ApiRunVariables extends java.util.LinkedHashMap<String,String> {
    private final java.util.Set<String> secrets=new java.util.LinkedHashSet<>();
    public void secret(String name,boolean secret){if(secret)secrets.add(name);else secrets.remove(name);}
    public java.util.Set<String> secretNames(){return java.util.Set.copyOf(secrets);}
    @Override public String remove(Object name){secrets.remove(name);return super.remove(name);}
    @Override public void clear(){secrets.clear();super.clear();}
}
