package com.jactil.tickethelper.core.query;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用仓库里录下来的真实 G2907 响应片段做断言：任何一个列索引错位都会让这些断言失败。
 */
class TicketParserTest {

    private static String realLine() throws IOException {
        try (var in = TicketParserTest.class.getResourceAsStream("/leftTicket-G2907.txt")) {
            assertNotNull(in, "缺少 fixture");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
    }

    private static String realLineWithField(int index, String value) throws IOException {
        String[] fields = realLine().split("\\|", -1);
        fields[index] = value;
        return String.join("|", fields);
    }

    @Test
    void parsesRealG2907Response() throws IOException {
        String json = LeftTicketQuery.toJson(realLine(), "广州南", "深圳北");

        List<TrainRow> trains = TicketParser.parse(json, code -> code);

        assertEquals(1, trains.size());
        TrainRow t = trains.get(0);
        assertEquals("G2907", t.trainNo());
        assertEquals("IZQ", t.fromCode());
        assertEquals("IOQ", t.toCode());
        assertEquals("广州南", t.fromName());
        assertEquals("深圳北", t.toName());
        assertEquals("11:56", t.startTime());
        assertEquals("12:32", t.arriveTime());
        assertEquals("00:36", t.duration());
        assertEquals("20260616", t.date());
        assertEquals("SECRET0", t.secretStr());
        assertEquals("1", t.trainLocation());
    }

    @Test
    void seatColumnsMatchTheRecordedResponse() throws IOException {
        TrainRow t = TicketParser.parse(LeftTicketQuery.toJson(realLine(), "广州南", "深圳北"), code -> code)
                .get(0);

        SeatInfo second = t.seat("二等座");
        assertEquals(3, second.count(), "二等座应为 3 张（记录值 [32]='3'）");
        assertTrue(second.available());
        assertTrue(second.enough(3));
        assertFalse(second.enough(4));

        assertEquals(0, t.seat("一等座").count(), "一等座记录值为 [31]='无'");
        assertFalse(t.seat("一等座").available());

        // [26]='无'。24/26 到底是「无座」还是「软座」见 SeatCatalog 的说明，待实盘复核。
        assertEquals(0, t.seat("软座").count(), "软座记录值为 [26]='无'");
        assertEquals(SeatInfo.ABSENT, t.seat("无座").count(), "无座记录值为 [24]=''");

        assertEquals(SeatInfo.ABSENT, t.seat("硬卧").count(), "硬卧记录值为空");
        assertFalse(t.seat("硬卧").exists());
    }

    @Test
    void youMeansAvailableWithUnknownCount() throws IOException {
        String line = realLineWithField(32, "有");

        TrainRow t = TicketParser.parse(LeftTicketQuery.toJson(line, "广州南", "深圳北"), code -> code).get(0);

        assertEquals(SeatInfo.UNKNOWN_AVAILABLE, t.seat("二等座").count());
        assertTrue(t.seat("二等座").enough(99));
    }

    @Test
    void soldOutSeatIsNotEnough() throws IOException {
        String line = realLineWithField(32, "无");

        TrainRow t = TicketParser.parse(LeftTicketQuery.toJson(line, "广州南", "深圳北"), code -> code).get(0);

        assertEquals(0, t.seat("二等座").count());
        assertFalse(t.seat("二等座").enough(1));
    }

    @Test
    void missingResultRaises() {
        assertThrows(IOException.class,
                () -> TicketParser.parse("{\"data\":{}}", code -> code));
    }

    @Test
    void stationNamesFallBackToCodes() throws IOException {
        String json = "{\"data\":{\"result\":[\"" + realLine() + "\"]}}";

        TrainRow t = TicketParser.parse(json, code -> "站" + code).get(0);

        assertEquals("站IZQ", t.fromName());
    }
}
