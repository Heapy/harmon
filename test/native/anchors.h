#ifndef HARMON_TEST_ANCHORS_H
#define HARMON_TEST_ANCHORS_H

/*
 * Comparing a sample against a second reading of the same kernel source, which is
 * what separates "the numbers look plausible" from "this number came out of that
 * field". Every suite that reads live machine state needs it, so the two shapes it
 * comes in live here rather than being written out per suite.
 *
 * A *tolerance* is for a source read twice in a row: the two readings may have
 * moved by whatever the machine did between them, and each suite documents its
 * allowance at the constant with what it was measured at. A tolerance wider than
 * the value it is applied to checks nothing, so an allowance is chosen against the
 * fields it has to separate, not against caution.
 *
 * A *bracket* is for a source read before *and* after the bridge reads it: a
 * counter that only grows is then pinned exactly by the pair, whatever the machine
 * did in between, and no allowance has to be guessed at all. Only a figure that
 * also falls needs slack on top.
 *
 * Both report the *first* field that disagrees by name, because a failure that
 * says "the sample is wrong" costs the reader the same search every time.
 */

#include <stddef.h>
#include <stdint.h>

static inline uint64_t hm_lowest(uint64_t first, uint64_t second) {
    return first < second ? first : second;
}

static inline uint64_t hm_highest(uint64_t first, uint64_t second) {
    return first > second ? first : second;
}

static inline uint64_t hm_absolute_difference(uint64_t first, uint64_t second) {
    return first > second ? first - second : second - first;
}

/* The same two for the counts the process suite brackets, which are `int`. */
static inline int hm_lowest_int(int first, int second) {
    return first < second ? first : second;
}

static inline int hm_highest_int(int first, int second) {
    return first > second ? first : second;
}

/* [value] lowered by [slack], floored at zero rather than wrapping. */
static inline uint64_t hm_below(uint64_t value, uint64_t slack) {
    return value > slack ? value - slack : 0;
}

/*
 * One field of a sample against the same field read again, and how far apart the
 * two reads may be. A tolerance of zero means the two reads must agree exactly.
 */
typedef struct {
    const char *name;
    uint64_t reported;
    uint64_t anchor;
    uint64_t tolerance;
} HMAnchoredField;

static inline const char *hm_first_mismatch(
    const HMAnchoredField *fields,
    size_t count,
    uint64_t *reported,
    uint64_t *anchor
) {
    for (size_t index = 0; index < count; ++index) {
        if (hm_absolute_difference(fields[index].reported, fields[index].anchor) <=
            fields[index].tolerance) {
            continue;
        }
        *reported = fields[index].reported;
        *anchor = fields[index].anchor;
        return fields[index].name;
    }
    return NULL;
}

/*
 * One field of a sample against the range the same field occupied around the
 * bridge's read, and the first field that fell outside its range.
 */
typedef struct {
    const char *name;
    uint64_t reported;
    uint64_t low;
    uint64_t high;
} HMBracketedField;

static inline const char *hm_first_outside_range(
    const HMBracketedField *fields,
    size_t count,
    uint64_t *reported,
    uint64_t *low,
    uint64_t *high
) {
    for (size_t index = 0; index < count; ++index) {
        if (fields[index].reported >= fields[index].low &&
            fields[index].reported <= fields[index].high) {
            continue;
        }
        *reported = fields[index].reported;
        *low = fields[index].low;
        *high = fields[index].high;
        return fields[index].name;
    }
    return NULL;
}

/*
 * The table is always a local array, so its length is always
 * `sizeof(fields) / sizeof(fields[0])` — spelled out at each of the five call
 * sites before, which is five chances to pass the count of a different table.
 */
#define HM_FIRST_MISMATCH(fields, reported, anchor) \
    hm_first_mismatch((fields), sizeof(fields) / sizeof((fields)[0]), (reported), (anchor))

#define HM_FIRST_OUTSIDE_RANGE(fields, reported, low, high)          \
    hm_first_outside_range(                                          \
        (fields),                                                    \
        sizeof(fields) / sizeof((fields)[0]),                        \
        (reported),                                                  \
        (low),                                                       \
        (high)                                                       \
    )

#endif
