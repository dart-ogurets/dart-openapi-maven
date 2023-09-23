part of k8s_api.api;

// this is a kubernetes style custom class
class IntOrString {
  String? value;

  static List<IntOrString> listFromJson(dynamic json) {
    return (json as List)
        .map((e) => IntOrString()..value = e.toString())
        .toList();
  }

  IntOrString copyWith() {
    return IntOrString()..value = this.value;
  }

  dynamic toJson() {
    return this.value;
  }

  static IntOrString fromJson(dynamic json) {
    return IntOrString()..value = json != null ? json.toString() : null;
  }
}
