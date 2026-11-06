package com.hgtv.clean;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Small deterministic checks for protocol hashes, encodings and optional reference vectors. */
public final class ProtocolSignerChecks {
    private ProtocolSignerChecks() { }

    /** Runs without Android or network access; optional arguments select reference header files. */
    public static void main(String[] arguments) throws Exception {
        check(RequestSigner.hex(RequestSigner.sm3("abc".getBytes(StandardCharsets.UTF_8))).equals("66c7f0f462eeedd9d1f2d46bdc10e4e24167c4875cf2f7a2297da02b8f4ba8e0"), "SM3 abc");
        check(RequestSigner.hex(RequestSigner.sm3("abcd".repeat(16).getBytes(StandardCharsets.UTF_8))).equals("debe9ff92275b8a138604889c18e5a4d6fdb70e5387e5765293dcba39c0c5732"), "SM3 full block");
        check(ProtocolTables.SUBSTITUTIONS.length == 1024 && ProtocolTables.ROUND_CONSTANTS.length == 64, "protocol table lengths");
        LinkedHashMap<String,String> query = new LinkedHashMap<>(); query.put("query","中文 a+b"); query.put("aid","8662"); query.put("device_id","0");
        check(ProtocolHttp.query(query).equals("query=%E4%B8%AD%E6%96%87+a%2Bb&aid=8662&device_id=0"), "ordered UTF-8 query");
        Map<String,String> profile = new LinkedHashMap<>(); profile.put("device_id","0");profile.put("version_name","7.2.2.32");profile.put("os_version","12");profile.put("os_api","32");profile.put("device_type","ProtocolTest");profile.put("device_brand","test");profile.put("rom_version","test-build");
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        RequestSigner.Signed first = RequestSigner.sign("https://api5-normal-sinfonlineb.fqnovel.com/test",query,body,profile,1700000000000L,()->0.25);
        RequestSigner.Signed second = RequestSigner.sign("https://api5-normal-sinfonlineb.fqnovel.com/test",query,body,profile,1700000000000L,()->0.25);
        check(first.url.equals(second.url) && first.headers.equals(second.headers), "deterministic request");
        check(first.headers.get("x-ss-stub").equals("99914B932BD37A50B983C5E7C90AE93B"), "body MD5");
        check(first.headers.get("x-gorgon").length()==52, "Gorgon wire length");
        check(first.headers.get("x-medusa").length()>500, "Medusa envelope");
        byte[] registration=DeviceRegistration.envelope("registration-self-check".getBytes(StandardCharsets.UTF_8));
        check(RequestSigner.hex(java.util.Arrays.copyOf(registration,6)).equals("746305100000"),"registration envelope marker");
        java.security.MessageDigest sha=java.security.MessageDigest.getInstance("SHA-512");
        byte[] derived=sha.digest(RequestSigner.concat(sha.digest(java.util.Arrays.copyOfRange(registration,6,38)),RequestSigner.unhex("4dd4c2e6b83162090e52b3c7a6733ba41cb2462b829ab58a196b39db57177524f49baf7f08e8d68d26a72e37c1a95a2f1f05a51892aef2949732b62a38aadd58")));
        javax.crypto.Cipher cipher=javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(javax.crypto.Cipher.DECRYPT_MODE,new javax.crypto.spec.SecretKeySpec(java.util.Arrays.copyOfRange(derived,0,16),"AES"),new javax.crypto.spec.IvParameterSpec(java.util.Arrays.copyOfRange(derived,16,32)));
        byte[] decoded=cipher.doFinal(java.util.Arrays.copyOfRange(registration,38,registration.length)),compressed=java.util.Arrays.copyOfRange(decoded,64,decoded.length);
        check(java.util.Arrays.equals(sha.digest(compressed),java.util.Arrays.copyOf(decoded,64)),"registration digest");
        try(java.util.zip.GZIPInputStream gzip=new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(compressed))) {
            check(new String(gzip.readAllBytes(),StandardCharsets.UTF_8).equals("registration-self-check"),"registration compression and encryption");
        }
        if (arguments.length>0) {
            Map<String,String> actual=new LinkedHashMap<>(first.headers);actual.put("url",first.url);
            for (String line:Files.readAllLines(Path.of(arguments[0]),StandardCharsets.UTF_8)) {
                if(line.isEmpty())continue;int equals=line.indexOf('=');check(equals>0,"reference line format");
                String name=line.substring(0,equals), expected=line.substring(equals+1);check(expected.equals(actual.get(name)),"reference "+name);
            }
        }
        if(arguments.length>1) {
            StringBuilder text=new StringBuilder("url="+first.url+"\n");for(Map.Entry<String,String>header:first.headers.entrySet())text.append(header.getKey()).append('=').append(header.getValue()).append('\n');
            Files.writeString(Path.of(arguments[1]),text.toString(),StandardCharsets.UTF_8);
        }
        if(arguments.length>2) {
            Map<String,String> actual=null;int count=0;
            for(String line:Files.readAllLines(Path.of(arguments[2]),StandardCharsets.UTF_8)) {
                if(line.startsWith("[case=")) {
                    int vector=Integer.parseInt(line.substring(6,line.length()-1));
                    LinkedHashMap<String,String> inputs=new LinkedHashMap<>();inputs.put("query","中文 a+b ~*_"+vector);inputs.put("aid","8662");inputs.put("device_id","0");
                    final int[] calls={0};
                    RequestSigner.Signed value=RequestSigner.sign("https://api5-normal-sinfonlineb.fqnovel.com/test",inputs,vector%2==0?body:null,profile,1700000000000L+vector*1000L,()->((vector+calls[0]++)%8)/8.0);
                    actual=new LinkedHashMap<>(value.headers);actual.put("url",value.url);count++;
                } else if(!line.isEmpty()) { int equals=line.indexOf('=');check(equals>0&&actual!=null,"vector line format");String name=line.substring(0,equals);
                    check(line.substring(equals+1).equals(actual.get(name)),"vector "+(count-1)+" "+name); }
            }
            check(count==24,"24 reference requests");
        }
        System.out.println("Protocol signer checks passed: 25 reference signatures, SM3 vectors, registration envelope; no network requests or account data used.");
    }
    private static void check(boolean condition,String name){if(!condition)throw new AssertionError(name);}
}
