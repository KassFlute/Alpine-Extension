// Function
let main = print("Hello, World!")

// Record
let x = #pair(1, 2)

// Boolean
let y = true
let z = false

// Numerics
let x = 8
let y = 9.0

// String
let s = "Hello, World!"

// Mixed record
let u = #number(42, endianness: #little)

// Big function with record fields selection
fun scale(_ p: #vector2(x: Float, y: Float), by f: Float) -> #vector2(x: Float, y: Float) {
  #vector2(x: f * p.x, y: f * p.1)
}

let main = print(scale(#vector2(x: 1.3, y: 2.1), by: 2.0))

// Function
fun duplicate(_ x: Any) -> #pair(Any, Any) {
  #pair(x, x)
}

let main = print(duplicate(#unit))

// Downcasting
let x2: Int = 40
let x3: Int = 2
let main = print((x2 @! Int) + x3)

// Type widening
let x = 42 @ Any

let z: Bool = 9

// Union type
let x: #a | #b = #a

// Instances
let v = #vector2(x: 1.0, y: 2.0)
let c = #circle(origin: v, radius: 5.0)
let r = #rectangle(origin: v, dimension: #vector2(x: 3.0, y: 4.0))

let main = print(v)
let main = print(c)
let main = print(r)

// List
let empty_list = #empty
let non_empty_list = #list(head: 1, tail: #list(head: 2, tail: #empty))

let main = print(empty_list)
let main = print(non_empty_list)

