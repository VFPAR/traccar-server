package org.traccar.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.Channel;
import org.traccar.BaseProtocolDecoder;
import org.traccar.NetworkMessage;
import org.traccar.Protocol;
import org.traccar.helper.BcdUtil;
import org.traccar.helper.BitUtil;
import org.traccar.helper.DateBuilder;
import org.traccar.helper.Parser;
import org.traccar.helper.PatternBuilder;
import org.traccar.session.DeviceSession;
import org.traccar.model.Position;

import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.regex.Pattern;

public class MicodusProtocolDecoder extends BaseProtocolDecoder {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter
            .ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC);

    // O pacote de texto (*HQ) manda speed/course sem confiança (0 mesmo em movimento) e não traz power.
    private static final String KEY_PACKET_TYPE = "packetType";
    private static final String PACKET_TYPE_TEXT = "text";
    private static final String PACKET_TYPE_BINARY = "binary";

    public MicodusProtocolDecoder(Protocol protocol) {
        super(protocol);
    }

    private void processStatus(Position position, long status) {
        position.set(Position.KEY_IGNITION, BitUtil.check(status, 10));
        position.set(Position.KEY_STATUS, status);
    }

    private Integer decodeBattery(int value) {
        if (value == 0) {
            return null;
        } else if (value <= 3) {
            return (value - 1) * 10;
        } else if (value <= 6) {
            return (value - 1) * 20;
        } else {
            return null;
        }
    }

    private void sendResponse(Channel channel, SocketAddress remoteAddress, String id, String type) {
        if (channel != null && id != null) {
            String time = DATE_FORMAT.format(Instant.now());
            String response = String.format("*HQ,%s,V4,%s,%s#", id, type, time);
            channel.writeAndFlush(new NetworkMessage(response, remoteAddress));
        }
    }

    private static final Pattern PATTERN = new PatternBuilder()
            .text("*")
            .expression("..,")                   // manufacturer
            .number("(d+)?,")                    // id
            .expression("(V[^,]*),")             // type (V1, V4, etc)
            .number("(?:(dd)(dd)(dd))?,")        // time (hhmmss)
            .expression("([ABV])?,")             // validity
            .number("(dd)(dd.d+),([NS]),")       // latitude
            .number("(ddd)(dd.d+),([EW]),")      // longitude
            .number(" *(d+.?d*),")               // speed
            .number("(d+.?d*)?,")                // course
            .number("(?:(dd)(dd)(dd))?")         // date (ddmmyy)
            .number(",(x{8})")                   // status
            .number(",(d+),")                    // country
            .number("(d+),")                     // operator
            .number("(d+),")                     // basestation
            .number("(d+),")                     // district
            .number("(d+)")                      // battery
            .text("#")
            .compile();

    private Position decodeText(String sentence, Channel channel, SocketAddress remoteAddress) {

        Parser parser = new Parser(PATTERN, sentence);
        if (!parser.matches()) {
            return null;
        }

        String id = parser.next();
        DeviceSession deviceSession = getDeviceSession(channel, remoteAddress, id);
        if (deviceSession == null) {
            return null;
        }

        Position position = new Position(getProtocolName());
        position.setDeviceId(deviceSession.getDeviceId());

        String type = parser.next();
        sendResponse(channel, remoteAddress, id, type);

        DateBuilder dateBuilder = new DateBuilder();
        if (parser.hasNext(3)) {
            dateBuilder.setTime(parser.nextInt(0), parser.nextInt(0), parser.nextInt(0));
        }

        position.setValid("A".equals(parser.next()));

        position.setLatitude(parser.nextCoordinate(Parser.CoordinateFormat.DEG_MIN_HEM));
        position.setLongitude(parser.nextCoordinate(Parser.CoordinateFormat.DEG_MIN_HEM));

        position.setSpeed(parser.nextDouble(0));
        position.setCourse(parser.nextDouble(0));

        if (parser.hasNext(3)) {
            dateBuilder.setDateReverse(parser.nextInt(0), parser.nextInt(0), parser.nextInt(0));
        }
        position.setTime(dateBuilder.getDate());

        processStatus(position, parser.nextLong(16, 0));

        position.set("country", parser.nextInt());
        position.set("operator", parser.nextInt());
        position.set("basestation", parser.nextInt());
        position.set("district", parser.nextInt());
        position.set(Position.KEY_BATTERY_LEVEL, decodeBattery(parser.nextInt()));

        position.set(KEY_PACKET_TYPE, PACKET_TYPE_TEXT);

        return position;
    }

    private Position decodeBinary(ByteBuf buf, Channel channel, SocketAddress remoteAddress) {

        buf.readByte(); // marker

        String id = ByteBufUtil.hexDump(buf.readSlice(5));

        DeviceSession deviceSession = getDeviceSession(channel, remoteAddress, id);
        if (deviceSession == null) {
            return null;
        }

        Position position = new Position(getProtocolName());
        position.setDeviceId(deviceSession.getDeviceId());

        DateBuilder dateBuilder = new DateBuilder()
                .setHour(BcdUtil.readInteger(buf, 2))
                .setMinute(BcdUtil.readInteger(buf, 2))
                .setSecond(BcdUtil.readInteger(buf, 2))
                .setDay(BcdUtil.readInteger(buf, 2))
                .setMonth(BcdUtil.readInteger(buf, 2))
                .setYear(BcdUtil.readInteger(buf, 2));
        position.setTime(dateBuilder.getDate());

        int latitudeRaw = BcdUtil.readInteger(buf, 8);
        double latitude = latitudeRaw / 1000000 + (latitudeRaw % 1000000) / 10000.0 / 60;

        buf.readByte(); // reserved

        int longitudeRaw = BcdUtil.readInteger(buf, 9);
        double longitude = longitudeRaw / 1000000 + (longitudeRaw % 1000000) / 10000.0 / 60;

        int flags = buf.readByte() & 0x0f;
        position.setValid(BitUtil.check(flags, 1));
        position.setLatitude(BitUtil.check(flags, 2) ? latitude : -latitude);
        position.setLongitude(BitUtil.check(flags, 3) ? longitude : -longitude);

        position.setSpeed(BcdUtil.readInteger(buf, 3));
        position.setCourse((buf.readUnsignedByte() & 0x0f) * 100 + BcdUtil.readInteger(buf, 2));

        processStatus(position, buf.readUnsignedInt());

        position.set("accAlarm", buf.readUnsignedByte());
        position.set("networkIndicator", buf.readUnsignedByte());
        position.set(Position.KEY_RSSI, buf.readUnsignedByte());
        int satellites = buf.readUnsignedByte();
        position.set(Position.KEY_SATELLITES, satellites & 0x0f);
        position.set("satellitesBeidou", satellites >> 4);

        position.set(Position.KEY_ODOMETER, buf.readUnsignedInt());

        position.set("country", buf.readUnsignedShort());

        position.set("satellitesGlonass", buf.readUnsignedByte());

        buf.skipBytes(4); // reserved

        position.set(Position.KEY_POWER, buf.readUnsignedShort() * 0.1);

        buf.skipBytes(4); // reserved

        position.set(Position.KEY_INDEX, buf.readUnsignedByte());

        position.set(KEY_PACKET_TYPE, PACKET_TYPE_BINARY);

        return position;
    }

    @Override
    protected Object decode(
            Channel channel, SocketAddress remoteAddress, Object msg) throws Exception {

        ByteBuf buf = (ByteBuf) msg;
        int marker = buf.getUnsignedByte(buf.readerIndex());

        if (marker == '*') {
            String sentence = buf.toString(StandardCharsets.US_ASCII).trim();
            return decodeText(sentence, channel, remoteAddress);
        } else if (marker == 0x24) {
            return decodeBinary(buf, channel, remoteAddress);
        }

        return null;
    }

}
