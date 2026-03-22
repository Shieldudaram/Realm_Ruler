package com.Chris__.realm_ruler.ui.pages.ctf;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;

public final class CtfPageEventData {

    public static final BuilderCodec<CtfPageEventData> CODEC =
            BuilderCodec.builder(CtfPageEventData.class, CtfPageEventData::new)
                    .append(new KeyedCodec<>("Action", Codec.STRING), CtfPageEventData::setAction, CtfPageEventData::getAction)
                    .add()
                    .append(new KeyedCodec<>("Value", Codec.STRING), CtfPageEventData::setValue, CtfPageEventData::getValue)
                    .add()
                    .build();

    private String action = "";
    private String value = "";

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = (action == null) ? "" : action;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = (value == null) ? "" : value;
    }
}
