(function (input, host) {
    "use strict";
    var stringify = JSON.stringify, own = Function.call.bind(Object.prototype.hasOwnProperty);
    var MAX = 2097152, writes = 0, envWrites = Object.create(null), globalWrites = Object.create(null);
    function fail(type) { host.fail(type); }
    function text(value, limit) {
        if (typeof value !== "string" || value.length > limit) fail("INPUT_LIMIT");
        return value;
    }
    function key(name, writing) {
        text(name, 256); if (!name.length) fail("INPUT_ERROR");
        if (writing && (name.charAt(0) === "$" || name.indexOf("run.") === 0 || name.indexOf("data.") === 0)) fail("UNSUPPORTED_API");
        return name;
    }
    function primitive(value) {
        if (value !== null && typeof value !== "string" && typeof value !== "number" && typeof value !== "boolean") fail("INPUT_ERROR");
        var result = String(value); if (result.length > 65536) fail("OUTPUT_LIMIT"); return result;
    }
    function copy(values) {
        var out = Object.create(null); if (values == null) return out;
        if (typeof values !== "object" || Array.isArray(values) || Object.keys(values).length > 1000) fail("INPUT_LIMIT");
        Object.keys(values).forEach(function (name) { key(name, false); var v = values[name]; if (v !== null && typeof v !== "string" && typeof v !== "number" && typeof v !== "boolean") fail("INPUT_ERROR"); out[name] = v; });
        return out;
    }
    function read(values, name) {
        key(name, false); var v = own(values, name) ? values[name] : undefined;
        if (typeof v === "string" && v.indexOf("secret://") === 0) fail("UNRESOLVED_SECRET"); return v;
    }
    var env = copy(input.environment), globals = copy(input.globals), vars = copy(input.variables), localWrites = Object.create(null);
    function resolve(name) {
        if (own(vars, name)) return read(vars, name);
        if (own(env, name)) return read(env, name);
        return read(globals, name);
    }
    function replace(value) {
        return text(value, MAX).replace(/\{\{\s*([^{}]+?)\s*\}\}/g, function (literal, name) { var v = resolve(name); return v === undefined ? literal : String(v); });
    }
    function api(values, changed, lookup) {
        function set(name, value, remove) {
            key(name, true); if (!own(changed, name) && ++writes > 1000) fail("OUTPUT_LIMIT");
            if (remove) { delete values[name]; changed[name] = null; }
            else { values[name] = primitive(value); changed[name] = values[name]; }
        }
        return Object.freeze({get: function (name) { return lookup ? resolve(name) : read(values, name); },
            set: function (name, value) { set(name, value, false); }, unset: function (name) { set(name, null, true); },
            has: function (name) { return (lookup ? resolve(name) : read(values, name)) !== undefined; }, replaceIn: replace,
            clear: function () { fail("UNSUPPORTED_API"); }, toObject: function () { fail("UNSUPPORTED_API"); }});
    }
    var rows = [], body = input.body == null ? null : text(input.body, MAX), bodyUpdated = false;
    var url = input.url == null ? "" : text(input.url, 65536), urlUpdated = false;
    function header(row) {
        if (!row || typeof row !== "object" || Array.isArray(row)) fail("INPUT_ERROR");
        var name = own(row, "key") ? row.key : row.name;
        text(name, 256); if (!name.length) fail("INPUT_ERROR");
        return {name: name, value: text(row.value, 65536), enabled: own(row, "enabled") ? row.enabled !== false : row.disabled !== true};
    }
    if (input.headers != null) {
        if (!Array.isArray(input.headers) || input.headers.length > 200) fail("INPUT_LIMIT");
        input.headers.forEach(function (row) { rows.push(header(row)); });
    }
    function add(row) { if (rows.length >= 200) fail("OUTPUT_LIMIT"); rows.push(header(row)); }
    function remove(name) { text(name, 256); rows = rows.filter(function (row) { return row.name.toLowerCase() !== name.toLowerCase(); }); }
    function get(name) { text(name, 256); for (var i = 0; i < rows.length; i++) if (rows[i].enabled && rows[i].name.toLowerCase() === name.toLowerCase()) return rows[i].value; return undefined; }
    var headers = Object.freeze({add: add, upsert: function (row) { var normalized = header(row); remove(normalized.name); add(normalized); },
        remove: remove, get: get, has: function (name) { return get(name) !== undefined; },
        toJSON: function () { return rows.map(function (row) { return {key: row.name, value: row.value, disabled: !row.enabled}; }); }});
    function updateBody(value) {
        if (value && typeof value === "object") { if (value.mode !== "raw") fail("UNSUPPORTED_API"); value = value.raw; }
        body = text(value, MAX); bodyUpdated = true;
    }
    var requestBody = {update: updateBody};
    Object.defineProperty(requestBody, "raw", {get: function () { return body; }, set: updateBody, enumerable: true});
    Object.freeze(requestBody);
    var requestUrl = Object.freeze({toString: function () { return url; }, toJSON: function () { return url; },
        getRaw: function () { return url; }, update: function (value) { url = text(value, 65536); urlUpdated = true; }});
    var request = {headers: headers, body: requestBody, method: text(input.method == null ? "GET" : input.method, 32)};
    Object.defineProperty(request, "url", {get: function () { return requestUrl; }, set: function (value) { requestUrl.update(value); }, enumerable: true});
    Object.freeze(request);
    function unsupported() { fail("UNSUPPORTED_API"); }
    var pm = Object.freeze({environment: api(env, envWrites, false), globals: api(globals, globalWrites, false), variables: api(vars, localWrites, true), request: request,
        sendRequest: unsupported, test: unsupported, expect: unsupported, execution: Object.freeze({setNextRequest: unsupported, skipRequest: unsupported}), collectionVariables: Object.freeze({get: unsupported, set: unsupported})});
    var console = Object.freeze({log: function () { host.console(); }, info: function () { host.console(); }, warn: function () { host.console(); }, error: function () { host.console(); }, debug: function () { host.console(); }});
    var nativeDate = Date, now = Number(input.timeMillis == null ? 0 : input.timeMillis);
    if (!isFinite(now)) fail("INPUT_ERROR");
    function FixedDate(a, b, c, d, e, f, g) {
        if (!(this instanceof FixedDate)) return new nativeDate(now).toString();
        switch (arguments.length) { case 0: return new nativeDate(now); case 1: return new nativeDate(a); case 2: return new nativeDate(a,b); case 3: return new nativeDate(a,b,c); case 4: return new nativeDate(a,b,c,d); case 5: return new nativeDate(a,b,c,d,e); case 6: return new nativeDate(a,b,c,d,e,f); default: return new nativeDate(a,b,c,d,e,f,g); }
    }
    FixedDate.prototype = nativeDate.prototype;
    Object.defineProperty(FixedDate.prototype, "constructor", {value: FixedDate, writable: false, configurable: false});
    FixedDate.now = function () { return now; }; FixedDate.parse = nativeDate.parse; FixedDate.UTC = nativeDate.UTC;
    Object.freeze(FixedDate);
    var Hex, Base64, Utf8;
    function wordArray(hex, generated) {
        text(hex, 12582912); if (hex.length % 2 || !/^[0-9a-f]*$/i.test(hex)) fail("SCRIPT_ERROR"); hex = hex.toLowerCase();
        var out = {sigBytes: hex.length / 2};
        Object.defineProperty(out, "_hex", {value: function () { return hex; }});
        out.toString = function (encoder) {
            var result; if (encoder === undefined || encoder === Hex) result = hex;
            else if (encoder === Base64) result = host.crypto("DECODE", "BASE64", hex, "HEX");
            else if (encoder === Utf8) result = host.crypto("DECODE", "UTF8", hex, "HEX");
            else fail("UNSUPPORTED_API");
            if (generated) host.secret(result); return result;
        };
        out.concat = function (other) { if (!other || typeof other._hex !== "function") fail("SCRIPT_ERROR"); hex += other._hex(); out.sigBytes = hex.length / 2; return out; };
        out.clone = function () { return wordArray(hex, generated); };
        out.clamp = function () { var n = Number(out.sigBytes); if (!isFinite(n) || n < 0 || Math.floor(n) !== n || n * 2 > hex.length) fail("SCRIPT_ERROR"); hex = hex.substring(0, n * 2); };
        Object.defineProperty(out, "words", {get: function () { var words = []; for (var i = 0; i < hex.length; i += 8) words.push(parseInt((hex.substring(i, i + 8) + "00000000").substring(0,8),16) | 0); return words; }});
        return out;
    }
    function material(value) {
        if (typeof value === "string") return {value: value, format: "UTF8"};
        if (value && typeof value._hex === "function") return {value: value._hex(), format: "HEX"};
        fail("SCRIPT_ERROR");
    }
    Hex = Object.freeze({parse: function (value) { return wordArray(text(value, 12582912), false); }, stringify: function (value) { return value.toString(Hex); }});
    Base64 = Object.freeze({parse: function (value) { return wordArray(host.crypto("ENCODE", "BASE64", text(value, MAX), "UTF8"), false); }, stringify: function (value) { return value.toString(Base64); }});
    Utf8 = Object.freeze({parse: function (value) { return wordArray(host.crypto("ENCODE", "UTF8", text(value, MAX), "UTF8"), false); }, stringify: function (value) { return value.toString(Utf8); }});
    function hash(algorithm, value) { var m = material(value); return wordArray(host.crypto("HASH", algorithm, m.value, m.format), true); }
    function hmac(algorithm, value, key) { var m = material(value), k = material(key); return wordArray(host.crypto("HMAC", algorithm, m.value, m.format, k.value, k.format), true); }
    var WordArray = Object.freeze({create: function (words, sigBytes) {
        if (words === undefined) words = []; if (!Array.isArray(words) || words.length > 524288) fail("INPUT_LIMIT");
        var hex = ""; words.forEach(function (v) { hex += ("00000000" + (v >>> 0).toString(16)).slice(-8); });
        if (sigBytes !== undefined) { if (sigBytes < 0 || Math.floor(sigBytes) !== sigBytes || sigBytes * 2 > hex.length) fail("SCRIPT_ERROR"); hex = hex.substring(0,sigBytes*2); }
        return wordArray(hex, false);
    }, random: function (bytes) { if (typeof bytes !== "number" || Math.floor(bytes) !== bytes) fail("INPUT_ERROR"); return wordArray(host.crypto("RANDOM", "", String(bytes), ""), true); }, parse: function (value, encoder) { if (encoder === undefined || encoder === Hex) return Hex.parse(value); if (encoder === Base64) return Base64.parse(value); if (encoder === Utf8) return Utf8.parse(value); fail("UNSUPPORTED_API"); }});
    var crypto = Object.freeze({HmacSHA1: function (v,k) { return hmac("HmacSHA1",v,k); }, HmacSHA256: function (v,k) { return hmac("HmacSHA256",v,k); }, HmacSHA512: function (v,k) { return hmac("HmacSHA512",v,k); },
        MD5: function (v) { return hash("MD5",v); }, SHA1: function (v) { return hash("SHA-1",v); }, SHA256: function (v) { return hash("SHA-256",v); }, SHA512: function (v) { return hash("SHA-512",v); },
        enc: Object.freeze({Hex: Hex, Base64: Base64, Utf8: Utf8}), lib: Object.freeze({WordArray: WordArray})});
    function require(name) { if (name !== "crypto-js") fail("UNSUPPORTED_API"); return crypto; }
    return {pm: pm, console: console, Date: FixedDate, random: function () { return host.random(); }, CryptoJS: crypto, require: require,
        finish: function () { return stringify({environmentWrites: envWrites, globalsWrites: globalWrites, localWrites: localWrites, headers: rows, body: body, bodyUpdated: bodyUpdated, url: url, urlUpdated: urlUpdated}); }};
})
