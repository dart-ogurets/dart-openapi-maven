import 'package:k8s_api/api.dart';
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

  test('additional properties mappings', () {
    const addProp = {
      'discrim': 'fred',
      'readings': {'one': 1, 'two': 2.3},
      'dependencies': {
        'deps1': ['a', 34.2, true],
        'deps2': [17.8, false, 'b']
      },
      'otherDeps': {
        'name': ['tom', 'dick', 'harry'],
        'height': [1.7, 1.3, 1.4],
        'info': 'this is top secret'
      },
      'yetMoreAdditional': {
        'sList': ['a', 'b', 'c']
      },
      'mapWithComplexObject': {
        'c1': [
          {
            'status': 'STREAMING',
            'id': 'xx',
            'title': 'Scully',
            'img': 'img',
            'imageUrl': 'http://blah'
          }
        ]
      },
      'mapWithEnums': {
        "statuses": ['STREAMING', 'CLOSED']
      },
    };

    var ap = AddProps3.fromJson(addProp);
    expect(ap.discrim, 'fred');
    expect(ap.readings.length, 2);
    expect(ap.readings['one'], 1);
    expect(ap.readings['two'], 2.3);
    expect(ap.dependencies['deps1'], ['a', 34.2, true]);
    expect(ap.dependencies['deps2'], [17.8, false, 'b']);
    expect(ap.otherDeps['name'], ['tom', 'dick', 'harry']);
    expect(ap.otherDeps['height'], [1.7, 1.3, 1.4]);
    expect(ap.otherDeps['info'], 'this is top secret');
    expect(ap.yetMoreAdditional['sList'], ['a', 'b', 'c']);
    expect(
        ap.mapWithComplexObject['c1'][0],
        Event()
          ..status = EventStatus.STREAMING
          ..id = 'xx'
          ..title = 'Scully'
          ..img = 'img'
          ..imageUrl = 'http://blah');
    expect(ap.mapWithEnums['statuses'], [EventStatus.STREAMING, EventStatus.CLOSED]);
  });
}
