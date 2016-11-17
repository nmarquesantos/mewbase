package com.tesco.mewbase.bson;

import io.vertx.core.json.JsonObject;

import java.util.Map;

public class BsonAuthenticationParser {

    public static JsonObject getDummyAuthInfo(BsonObject bsonAuthInfo) {
        Map<String, String> map = (Map<String, String>) bsonAuthInfo.getMap().get("map");

        JsonObject jsonObject = new JsonObject();
        jsonObject.put("username", map.get("username"));
        jsonObject.put("password", map.get("password"));

        return jsonObject;
    }
}
