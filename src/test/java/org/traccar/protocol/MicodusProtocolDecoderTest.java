package org.traccar.protocol;

import org.junit.jupiter.api.Test;
import org.traccar.ProtocolTest;

public class MicodusProtocolDecoderTest extends ProtocolTest {

    @Test
    public void testDecode() throws Exception {

        var decoder = inject(new MicodusProtocolDecoder(null));

        verifyPosition(decoder, buffer(
                "*HQ,4209809058,V1,064709,V,2233.9355,N,11351.7442,E,000.00,000,231215,FFFFFBFF,460,00,0,0,6#"));

        verifyPosition(decoder, binary(
                "2478010105171322240906202928064400106282727E000000FFFFFBFFFF00140C0000000101CC0500000000007C0000000002"));

        verifyAttribute(decoder, buffer(
                "*HQ,4209809058,V1,064709,V,2233.9355,N,11351.7442,E,000.00,000,231215,FFFFFBFF,460,00,0,0,6#"),
                "packetType", "text");

        verifyAttribute(decoder, binary(
                "2478010105171322240906202928064400106282727E000000FFFFFBFFFF00140C0000000101CC0500000000007C0000000002"),
                "packetType", "binary");

        // captured from a real MV730G unit (Vivo M2M SIM, no GPS fix yet)
        verifyPosition(decoder, binary(
                "24734001983020381625092600000000000000000000000000f7e7fbffff04140b0000000001cc02ff00000000000000000000"));

        // identification packets sent on connect (captured from MV730G)
        verifyAttribute(decoder, buffer(
                "*HQ,7340019820,V19,034824,V,1150.3065,S,05530.6183,W,000.00,000,151126,,,89550669469002354709,BFFFFBFF#"),
                "iccid", "89550669469002354709");

        verifyAttribute(decoder, buffer(
                "*HQ,7340019820,V15,034824,V,1150.3065,S,05530.6183,W,000.00,000,151126,,,867284068581430,BFFFFBFF#"),
                "imei", "867284068581430");

        // device ack to S20 (cut oil and power / restore), per MiCODUS protocol V2.3 section 2.11
        verifyAttribute(decoder, buffer(
                "*HQ,7340019820,V4,S20,095517,1,1#"),
                "blocked", true);

        verifyAttribute(decoder, buffer(
                "*HQ,7340019820,V4,S20,095517,1,0#"),
                "blocked", false);
    }
}
