import 'dart:convert';

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

  test('basic inheritance', () {
    final wld = WithListDerived.fromJson({
      "list": [ {"id": 1, "name": "one"}, {"id": 2, "name": "two"} ],
      "id": 7, "name": "entity",
      "nullableList": []
    });

    expect(wld.id, 7);
    expect(wld.name, "entity");
    expect(wld.list.length, 2);
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
        ap.mapWithComplexObject['c1']?[0],
        Event()
          ..status = EventStatus.STREAMING
          ..id = 'xx'
          ..title = 'Scully'
          ..img = 'img'
          ..imageUrl = 'http://blah');
    expect(ap.mapWithEnums['statuses'],
        [EventStatus.STREAMING, EventStatus.CLOSED]);
  });

  test("List<AnyOf<MyApple,MyBanana>> - parsing json array with discriminator",
      () {
    final items =
        AnyOfMyAppleMyBanana.listFromJson(jsonDecode(_dummyDiscriminatorJson));

    expect(items, hasLength(2));
    expect(items[0].discriminator, AnyOfDiscriminatorMyAppleMyBanana.MyApple);
    expect(items[0].asMyApple().type, "apple");
    expect(items[0].asMyApple().kind, "Foxwhelp");
    expect(items[1].discriminator, AnyOfDiscriminatorMyAppleMyBanana.MyBanana);
    expect(items[1].asMyBanana().type, "banana");
    expect(items[1].asMyBanana().count, 42);
  });
  test('double with ints in them tests and vs versa', () {
    const testData = {
      'basicInt': 2.6,
      'basicDouble': 1,
      'intList': [1, 2.6],
      'intMap': {'one': 1, 'two': 2.7},
      'doubleList': [1, 2.6],
      'doubleMap': {'one': 1, 'two': 2.7},
    };

    final data = DoubleAndIntConversion.fromJson(testData);
    expect(data.basicDouble, 1.0);
    expect(data.basicInt, 2);
    expect(data.intList, [1, 2]);
    expect(data.intMap, {'one': 1, 'two': 2});
    expect(data.doubleList, [1.0, 2.6]);
    expect(data.doubleMap, {'one': 1.0, 'two': 2.7});
  });
  test('data serialisation', () {
    final data = DoubleAndIntConversion(
        basicInt: 43, basicDouble: 26.2, intList: [], doubleMap: {});
    expect(data.toJson(), {
      'basicInt': 43, 'basicDouble': 26.2, 'intList': [],
      'doubleList': [], // because we can't tell between null and empty
      'doubleMap': {}
    });
  });
  test("int enums being generated with correct type", () {
    expect(IntTypeEnum.number1.toJson(), 1);
    expect(IntTypeEnum.number1, IntTypeEnumExtension.fromJson(1));
  });
  test(
      "enums included in a model via allOf with reference will be treated as"
      "enums and generate valid code ", () {
    final testO = ObjectContainingEnum.fromJson(
        {"name": "foobar", "enumFieldAllOf": "667"});
    expect(testO.name, "foobar");
    expect(testO.enumFieldAllOf, NumericAndSpacedEnum.n667);
  });
  test("generating 2d array in a correct way", () {
    const json = {
      "coordinates": [
        [-27.6307582, 153.0401564],
        [37.4220656, -122.0862784],
      ]
    };
    final geometry = PointGeometry.fromJson(json);
    expect(geometry.coordinates, hasLength(2));
    final firstPair = geometry.coordinates.first;
    expect(firstPair, hasLength(2));
    expect(firstPair[0], -27.6307582);
    expect(firstPair[1], 153.0401564);
  });
}

const _dummyDiscriminatorJson = r"""
[
  {
    "type": "apple",
    "kind": "Foxwhelp"
  },
  {
    "type": "banana",
    "count": 42
  }
]
""";
