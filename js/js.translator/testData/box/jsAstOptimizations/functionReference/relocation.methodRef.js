function Foo$bar$ref() {
  return constructCallableReference(function (p0) {
    p0.a();
    return Unit$instance;
  }, 1, 0, 1, 'bar');
}
