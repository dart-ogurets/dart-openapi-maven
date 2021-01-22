import 'package:sample_app/api.dart';
import 'package:test/test.dart';

main() {
  test('import conversion and compare works for issue-19', () {
    const waypoint = {
      "geocoderStatus": "OK",
      "partialMatch": true,
      "placeId": "place-id",
      "types": ["STREET_ADDRESS", "STREET_NUMBER"]
    };
    var w = GeocodedWaypoint.fromJson(waypoint);
    expect(w.geocoderStatus, GeocodedWaypointGeocoderStatusEnum.OK);
    expect(w.partialMatch, true);
    expect(w.placeId, "place-id");
    expect(w.types,
        [GeocodedWaypointTypesEnum.ADDRESS, GeocodedWaypointTypesEnum.NUMBER]);
    var x = w.copyWith();
    expect(w, x);
    expect(true, w == x);
    print(w);
    print(x);
    print("codes ${w.hashCode} vs ${x.hashCode}");
    print("empty array -> ${[].hashCode}");
    expect(true, w.hashCode == x.hashCode);
    var z = w.copyWith(types: [GeocodedWaypointTypesEnum.ADDRESS]);
    expect(false, w == z);
    expect(false, w.hashCode == z.hashCode);
    var encodeDecode = LocalApiClient.deserialize(
        LocalApiClient.serialize(w), 'GeocodedWaypoint');
    expect(encodeDecode, w);
  });

  // previous hashing mechanism if you swapped the adjacent field values
  // it wouldn't change the hash
  test(
      'hashing an object which has two fields of the same type is still different',
          () {
        var ht = HashTest()
          ..fieldOne = false
          ..fieldTwo = true;
        var ht1 = HashTest()
          ..fieldOne = true
          ..fieldTwo = false;
        expect(false, ht.hashCode == ht1.hashCode);
      });
}
