/*
 * Arrays, objects and iterators.
 *
 * Only the primitives that reach into a container's storage live here; anything
 * expressible as a fold over elements (concat, map, sort, ...) is written in SFL
 * and compiled like user code. The ones that remain are the mutating ones, and
 * they mutate: push, insert and their kin change the array in place and hand the
 * same object back, exactly as the interpreter's ArrayBuffer does, so two names
 * bound to one array keep seeing each other's writes.
 *
 * Objects keep insertion order, so keys/values/entries walk the entry arrays
 * directly rather than going through the hash index.
 */
#include "sfl.h"

#include <stdio.h>
#include <string.h>

/* ------------------------------------------------------------------------- */
/* Shared helpers                                                             */
/* ------------------------------------------------------------------------- */

/* Negative indices count back from the end, exactly as Index.normalize does. */
static int64_t normalize(int64_t i, int64_t len) { return i < 0 ? len + i : i; }

/* Grows the element buffer to hold `need` items, doubling as ArrayBuffer does.
   Only the raw allocator runs here, so no collection can happen mid-move. */
static void arr_reserve(SflVal a, int64_t need) {
  if (need <= (int64_t)a->u.a.cap) return;
  int64_t cap = a->u.a.cap > 0 ? a->u.a.cap : 4;
  while (cap < need) cap *= 2;
  a->u.a.items = (SflVal *)sfl_raw_realloc(a->u.a.items, (size_t)cap * sizeof(SflVal));
  a->u.a.cap = (int32_t)cap;
}

/* ------------------------------------------------------------------------- */
/* Arrays                                                                     */
/* ------------------------------------------------------------------------- */

SflVal sfl_p_array(int64_t argc, SflVal *argv) { return sfl_arr_of(argc, argv); }

SflVal sfl_p_newArray(int64_t argc, SflVal *argv) {
  int32_t n = sfl_arg_idx(argv, 0, "newArray");
  if (n < 0) sfl_raise_sig("newArray", "length must not be negative");
  if (n > 50000000) sfl_raise_sig("newArray", "length %d is too large", n);
  SflVal fill = sfl_opt(argc, argv, 1);
  SflVal a = sfl_arr_new(n);
  for (int32_t i = 0; i < n; i++) a->u.a.items[i] = fill;
  a->aux = (uint32_t)n;
  return a;
}

/* Java's long division, whose one case C leaves undefined. */
static int64_t jdiv(int64_t a, int64_t b) {
  if (a == INT64_MIN && b == -1) return INT64_MIN;
  return a / b;
}

SflVal sfl_p_range(int64_t argc, SflVal *argv) {
  int64_t start, end, step;
  if (argc == 1) {
    start = 0;
    end = sfl_arg_int(argv, 0, "range");
    step = 1;
  } else {
    start = sfl_arg_int(argv, 0, "range");
    end = sfl_arg_int(argv, 1, "range");
    step = argc > 2 ? sfl_arg_int(argv, 2, "range") : 1;
  }
  if (step == 0) sfl_raise_sig("range", "step must not be zero");

  /* The count is computed in wrapping arithmetic because the interpreter's Long
     arithmetic wraps, and an absurd span has to be rejected the same way here. */
  uint64_t span = (uint64_t)end - (uint64_t)start;
  int64_t count = step > 0 ? jdiv((int64_t)(span + (uint64_t)step - 1), step)
                           : jdiv((int64_t)(span + (uint64_t)step + 1), step);
  if (count > 50000000LL) sfl_raise_sig("range", "would produce %lld elements", (long long)count);

  SflVal out = sfl_arr_new(count > 0 ? count : 1);
  int64_t v = start;
  while (step > 0 ? v < end : v > end) {
    sfl_arr_push(out, sfl_int(v));
    v = (int64_t)((uint64_t)v + (uint64_t)step); /* wraps rather than trapping */
  }
  return out;
}

SflVal sfl_p_push(int64_t argc, SflVal *argv) {
  SflVal arr = sfl_arg_arr(argv, 0, "push");
  for (int64_t i = 1; i < argc; i++) sfl_arr_push(arr, argv[i]);
  return arr;
}

SflVal sfl_p_pop(int64_t argc, SflVal *argv) {
  SflVal arr = sfl_arg_arr(argv, 0, "pop");
  if (arr->aux == 0) sfl_raise_sig("pop", "array is empty");
  arr->aux--;
  return arr->u.a.items[arr->aux];
}

SflVal sfl_p_shift(int64_t argc, SflVal *argv) {
  SflVal arr = sfl_arg_arr(argv, 0, "shift");
  if (arr->aux == 0) sfl_raise_sig("shift", "array is empty");
  SflVal v = arr->u.a.items[0];
  memmove(arr->u.a.items, arr->u.a.items + 1, (size_t)(arr->aux - 1) * sizeof(SflVal));
  arr->aux--;
  return v;
}

SflVal sfl_p_unshift(int64_t argc, SflVal *argv) {
  SflVal arr = sfl_arg_arr(argv, 0, "unshift");
  int64_t len = (int64_t)arr->aux, extra = argc - 1;
  arr_reserve(arr, len + extra);
  memmove(arr->u.a.items + extra, arr->u.a.items, (size_t)len * sizeof(SflVal));
  for (int64_t i = 0; i < extra; i++) arr->u.a.items[i] = argv[i + 1];
  arr->aux = (uint32_t)(len + extra);
  return arr;
}

SflVal sfl_p_insert(int64_t argc, SflVal *argv) {
  SflVal arr = sfl_arg_arr(argv, 0, "insert");
  int64_t len = (int64_t)arr->aux;
  int64_t i = normalize(sfl_arg_idx(argv, 1, "insert"), len);
  /* i == len is allowed: inserting before the end appends. */
  if (i < 0 || i > len) sfl_raise_sig("insert", "index out of bounds (length %lld)", (long long)len);
  arr_reserve(arr, len + 1);
  memmove(arr->u.a.items + i + 1, arr->u.a.items + i, (size_t)(len - i) * sizeof(SflVal));
  arr->u.a.items[i] = argv[2];
  arr->aux = (uint32_t)(len + 1);
  return arr;
}

SflVal sfl_p_removeAt(int64_t argc, SflVal *argv) {
  SflVal arr = sfl_arg_arr(argv, 0, "removeAt");
  int64_t len = (int64_t)arr->aux;
  int64_t i = normalize(sfl_arg_idx(argv, 1, "removeAt"), len);
  if (i < 0 || i >= len) sfl_raise_sig("removeAt", "index out of bounds (length %lld)", (long long)len);
  SflVal v = arr->u.a.items[i];
  memmove(arr->u.a.items + i, arr->u.a.items + i + 1, (size_t)(len - i - 1) * sizeof(SflVal));
  arr->aux = (uint32_t)(len - 1);
  return v;
}

SflVal sfl_p_clear(int64_t argc, SflVal *argv) {
  SflVal v = argv[0];
  if (v->tag == SFL_ARR) {
    v->aux = 0;
    return v;
  }
  if (v->tag == SFL_OBJ) {
    v->aux = 0;
    /* The hash index has to go with the entries: it maps names to slots past the
       new end, whose keys the collector no longer keeps alive. */
    sfl_raw_free(v->u.o.index);
    v->u.o.index = NULL;
    v->u.o.index_cap = 0;
    return v;
  }
  sfl_raise_sig("clear", "expected an array or object, got %s", sfl_type_name(v));
}

/* ------------------------------------------------------------------------- */
/* Objects                                                                    */
/* ------------------------------------------------------------------------- */

SflVal sfl_p_object(int64_t argc, SflVal *argv) { return sfl_obj_new(); }

SflVal sfl_p_keys(int64_t argc, SflVal *argv) {
  SflVal o = sfl_arg_obj(argv, 0, "keys");
  SflVal out = sfl_arr_new((int64_t)o->aux);
  for (uint32_t i = 0; i < o->aux; i++) out->u.a.items[i] = o->u.o.keys[i];
  out->aux = o->aux;
  return out;
}

SflVal sfl_p_values(int64_t argc, SflVal *argv) {
  SflVal o = sfl_arg_obj(argv, 0, "values");
  SflVal out = sfl_arr_new((int64_t)o->aux);
  for (uint32_t i = 0; i < o->aux; i++) out->u.a.items[i] = o->u.o.vals[i];
  out->aux = o->aux;
  return out;
}

SflVal sfl_p_entries(int64_t argc, SflVal *argv) {
  SflVal o = sfl_arg_obj(argv, 0, "entries");
  SflVal out = sfl_arr_new((int64_t)o->aux);
  for (uint32_t i = 0; i < o->aux; i++) {
    SflVal pair[2];
    pair[0] = o->u.o.keys[i];
    pair[1] = o->u.o.vals[i];
    /* `out` and `pair` are locals, so both survive the allocation below. */
    sfl_arr_push(out, sfl_arr_of(2, pair));
  }
  return out;
}

SflVal sfl_p_has(int64_t argc, SflVal *argv) {
  SflVal o = sfl_arg_obj(argv, 0, "has");
  SflVal key = sfl_arg_str(argv, 1, "has");
  return sfl_bool(sfl_obj_get(o, key) != NULL);
}

SflVal sfl_p_get(int64_t argc, SflVal *argv) {
  SflVal fallback = sfl_opt(argc, argv, 2);
  SflVal v = argv[0];
  switch (v->tag) {
    case SFL_OBJ: {
      SflVal got = sfl_obj_get(v, sfl_arg_str(argv, 1, "get"));
      return got ? got : fallback;
    }
    case SFL_ARR: {
      int64_t i = normalize(sfl_arg_idx(argv, 1, "get"), (int64_t)v->aux);
      if (i < 0 || i >= (int64_t)v->aux) return fallback;
      return v->u.a.items[i];
    }
    case SFL_NULL:
      /* Reading through null yields the default, which is what makes get() the
         safe way to walk a structure that may not have the shape expected. */
      return fallback;
    default:
      sfl_raise_sig("get", "expected an object or array, got %s", sfl_type_name(v));
  }
}

SflVal sfl_p_set(int64_t argc, SflVal *argv) {
  sfl_index_set(argv[0], argv[1], argv[2]);
  return argv[0];
}

SflVal sfl_p_remove(int64_t argc, SflVal *argv) {
  SflVal o = sfl_arg_obj(argv, 0, "remove");
  SflVal key = sfl_arg_str(argv, 1, "remove");
  SflVal v = sfl_obj_get(o, key);
  if (v == NULL) return sfl_null;
  sfl_obj_remove(o, key);
  return v;
}

int64_t sfl_size_i(int64_t argc, SflVal *argv) {
  SflVal v = argv[0];
  switch (v->tag) {
    case SFL_STR:
    case SFL_ARR:
    case SFL_TUPLE:
    case SFL_OBJ:
      return (int64_t)v->aux;
    default:
      sfl_raise_sig("size", "%s has no size", sfl_type_name(v));
  }
}

SflVal sfl_p_size(int64_t argc, SflVal *argv) { return sfl_int(sfl_size_i(argc, argv)); }

/* ------------------------------------------------------------------------- */
/* Iterators                                                                  */
/* ------------------------------------------------------------------------- */

SflVal sfl_p_iter(int64_t argc, SflVal *argv) { return sfl_iter_begin(argv[0]); }

SflVal sfl_p_hasNext(int64_t argc, SflVal *argv) {
  SflVal it = argv[0];
  if (it->tag != SFL_ITER) sfl_raise_sig("hasNext", "expected an iterator, got %s", sfl_type_name(it));
  return sfl_bool(sfl_iter_has_next(it));
}

SflVal sfl_p_iterNext(int64_t argc, SflVal *argv) {
  SflVal it = argv[0];
  if (it->tag != SFL_ITER) sfl_raise_sig("iterNext", "expected an iterator, got %s", sfl_type_name(it));
  /* One step rather than hasNext-then-next: over a lazy source that is the same
     number of calls to the driving function, and it cannot disagree with itself. */
  SflVal v = sfl_null;
  if (!sfl_iter_next(it, &v)) sfl_raise_sig("iterNext", "the iterator is exhausted");
  return v;
}

SflVal sfl_p___iterFn(int64_t argc, SflVal *argv) {
  return sfl_iter_from_fn(sfl_arg_fn(argv, 0, "__iterFn"));
}

/* Builds a tuple from an array's elements; the inverse of toArray. */
SflVal sfl_p_tupleOf(int64_t argc, SflVal *argv) {
  SflVal v = argv[0];
  if (v->tag == SFL_TUPLE) return v;
  if (v->tag != SFL_ARR)
    sfl_raise_sig("tupleOf", "argument 1 must be an array, got %s", sfl_type_name(v));
  if (v->aux < 2) sfl_raise_sig("tupleOf", "a tuple needs at least two elements");
  return sfl_tuple_of((int64_t)v->aux, v->u.a.items);
}
