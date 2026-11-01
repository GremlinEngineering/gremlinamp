package ru.oopssre.gremlinamp;
import java.time.*;
final class SyncRules {
 static long untilMorning(long now){ZoneId zone=ZoneId.of("Europe/Moscow");ZonedDateTime current=Instant.ofEpochMilli(now).atZone(zone);ZonedDateTime next=current.toLocalDate().atTime(7,0).atZone(zone);if(!next.isAfter(current))next=next.plusDays(1);return next.toInstant().toEpochMilli()-now;}
}
