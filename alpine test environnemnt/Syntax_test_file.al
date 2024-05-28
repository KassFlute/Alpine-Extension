;; Function
let main = print("Hello, World!")

;; Record
let x = #pair(1, 2)

;; Mixed record
let u = #number(42, endianness: #little)

;; Big function with record fields selection
fun scale(_ p: #vector2(x: Float, y: Float), by f: Float) -> #vector2(x: Float, y: Float) {
  #vector2(x: f * p.x, y: f * p.1)
}

let main = print(scale(#vector2(x: 1.3, y: 2.1), by: 2.0))

;; Function
fun duplicate(_ x: Any) -> #pair(Any, Any) {
  #pair(x, x)
}

let main = print(duplicate(#unit))

;; Downcasting
let x: Any = 40
let main = print((x @! Int) + 2)

;; Type widening
let x = 42 @ Any

;; Union type
let x: #a | #b = #a

;; function
fun is_anonymous(_ p: #person | #person(name: String)) -> Bool {
  match p {
    case #person then true
    case #person(name: _) then false
  }
}

let main = print(is_anonymous(#person(name: "Hannah")))

;; function
fun name(of p: #person | #person(name: String)) -> #none | #some(String) {
  match p {
    case #person then #none
    case #person(name: let n) then #some(n)
  }
}

;; Another way
fun name(of p: #person | #person(name: String)) -> #none | #some(String) {
  match p {
    case #person then #none
    case let q: #person(name: String) then #some(q.name)
  }
}

;; @? operator
fun is_human(_ p: #person | #person(name: String) | #alien(name: String)) -> Bool {
  (p @? #alien(String)) != #none
}

;; Type def
type Vector2 = #vector2(x: Float, y: Float)
type Circle = #circle(origin: Vector2, radius: Float)
type Rectangle = #rectangle(origin: Vector2, dimension: Vector2)

;; Recursive type
type List = #empty | #list(head: Any, tail: List)

;; Instances
let v = #vector2(x: 1.0, y: 2.0)
let c = #circle(origin: v, radius: 5.0)
let r = #rectangle(origin: v, dimension: #vector2(x: 3.0, y: 4.0))

let main = print(v)
let main = print(c)
let main = print(r)

;; List
let empty_list = #empty
let non_empty_list = #list(head: 1, tail: #list(head: 2, tail: #empty))

let main = print(empty_list)
let main = print(non_empty_list)