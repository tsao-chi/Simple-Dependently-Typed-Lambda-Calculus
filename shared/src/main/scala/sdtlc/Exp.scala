package sdtlc

import scala.collection.immutable.HashMap

object SDTLC {
  type Nat = Int
}

import SDTLC._

final case class Context(inner: HashMap[Var, Exp]) {
  def extend(x: Var, t: Exp): Context = Context(inner.updated(x, t))

  def get(x: Var): Option[Exp] = inner.get(x)

  def getOrElse(x: Var, default: => Exp): Exp = inner.getOrElse(x, {
    default
  })

  def toList: List[(Var, Exp)] = inner.toList
}

object Context {
  val Empty: Context = Context(HashMap())
}

sealed trait Exp {
  def betaReduce: Exp

  final def betaEtaReduce: Exp = {
    val next = this.betaReduce.etaReduce
    if (next == this) {
      this
    } else {
      next.betaEtaReduce
    }
  }

  def etaReduce: Exp

  def subst(x: Var, v: Exp): Exp

  final def storeType(xs: List[(Var, Exp)]): Exp = xs match {
    case Nil => this
    case (v, t) :: xs => this.subst(v, The(t, v)).storeType(xs)
  }

  final def storeType(x: Context): Exp = this.storeType(x.toList)

  def infer(env: Context): Option[Exp]

  final def checkInfer(env: Context, t: Exp): Boolean = this.infer(env) match {
    case Some(t0) => t.equalType(t0)
    case None => false
  }

  def check(env: Context, t: Exp): Boolean = checkInfer(env, t)

  def equalType(other: Exp): Boolean = ???

  def level(env: Context): Option[Nat]

  def contains(v: Var): Boolean
}

final case class Apply(f: Exp, value: Exp) extends Exp {
  override def betaReduce: Exp = f.betaReduce match {
    case Lambda(arg, body) => {
      val nextStep = body.subst(arg, value)
      if (this == nextStep) {
        throw new IllegalStateException("Welcome to the Future!")
      } else {
        this.betaReduce
      }
    }
    case f => Apply(f, value.betaReduce)
  }

  override def subst(x: Var, v: Exp): Exp = Apply(f.subst(x, v), value.subst(x, v))

  override def infer(env: Context): Option[Exp] = f.infer(env) flatMap {
    case Pi(argPi, domainPi, codomainPi) if value.check(env, domainPi) => Some(codomainPi.subst(argPi, The(domainPi, value)))
    case _ => None
  }

  override def check(env: Context, t: Exp): Boolean = this.checkInfer(env, t)

  override def level(env: Context): Option[Nat] = for {
    l0 <- f.level(env)
    l1 <- value.level(env)
  } yield l0.max(l1)

  override def etaReduce: Exp = Apply(f.etaReduce, value.etaReduce)

  override def contains(v: Var): Boolean = f.contains(v) || value.contains(v)
}

final case class Var(x: Symbol) extends Exp {
  override def betaReduce: Exp = this

  override def subst(x: Var, v: Exp): Exp = if (x == this) {
    v
  } else {
    this
  }

  override def infer(env: Context): Option[Exp] = env.get(this)

  override def level(env: Context): Option[Nat] = env.get(this).flatMap(_.level(env)).flatMap(x => if (x == 0) {
    None
  } else {
    Some(x - 1)
  })

  override def etaReduce: Exp = this

  override def contains(v: Var): Boolean = v == this
}

object Var {
  def apply(x: Symbol): Var = new Var(x)

  def apply(x: String): Var = new Var(Symbol(x))
}

final case class The(t: Exp, x: Exp) extends Exp {
  override def betaReduce: Exp = x.betaReduce

  override def subst(x: Var, v: Exp): Exp = The(t.subst(x, v), x.subst(x, v))

  override def infer(env: Context): Option[Exp] = Some(t)

  override def level(env: Context): Option[Nat] =
    t.level(env).flatMap(x => if (x == 0) {
      None
    } else {
      Some(x - 1)
    }) orElse x.level(env)

  override def etaReduce: Exp = The(t.etaReduce, x.etaReduce)

  override def contains(v: Var): Boolean = t.contains(v) || x.contains(v)
}

final case class Lambda(arg: Var, exp: Exp) extends Exp {
  override def betaReduce: Exp = Lambda(arg, exp.betaReduce)

  override def subst(x: Var, v: Exp): Exp = if (x == arg) {
    this
  } else {
    Lambda(arg, exp.subst(x, v))
  }

  override def infer(env: Context): Option[Exp] = None

  override def check(env: Context, t: Exp): Boolean = t.betaReduce match {
    case Pi(argPi, domainPi, codomainPi) => exp.check(env.extend(arg, domainPi), codomainPi.subst(argPi, arg))
    case _ => false
  }

  override def level(env: Context): Option[Nat] = exp.level(env)

  override def etaReduce: Exp = this match {
    case Lambda(v0, Apply(f, v1)) if v0 == v1 && !f.contains(v0) => f
    case _ => Lambda(arg, exp.etaReduce)
  }

  override def contains(v: Var): Boolean = if (v == arg) {
    false
  } else {
    exp.contains(v)
  }
}

sealed trait ExpType extends Exp {
  override final def infer(env: Context): Option[Exp] = this.level(env).map(Universe(_))
}

// Universe(1) Universe(2) ...
final case class Universe(level: Exp) extends ExpType {
  lazy val natLevel: Option[Nat] = NatUtils.toHost(level)

  override def betaReduce: Exp = level.betaReduce

  override def subst(x: Var, v: Exp): Exp = Universe(level.subst(x, v))

  override def level(env: Context): Option[Nat] = Some(natLevel.getOrElse(1) + 1)

  override def etaReduce: Exp = Universe(level.etaReduce)

  override def contains(v: Var): Boolean = level.contains(v)
}

object Universe {
  def apply(level: Exp): Universe = new Universe(level)

  def apply(level: Nat): Universe = Universe(NatUtils.toExp(level))
}

final case class Pi(arg: Var, domain: Exp, codomain: Exp) extends ExpType {
  override def betaReduce: Exp = Pi(arg, domain.betaReduce, codomain.betaReduce)

  override def subst(x: Var, v: Exp): Exp = Pi(arg, domain.subst(x, v), if (x == arg) {
    codomain
  } else {
    codomain.subst(x, v)
  })

  override def level(env: Context): Option[Nat] = for {
    l0 <- domain.level(env)
    l1 <- codomain.level(env.extend(arg, domain))
  } yield l0.max(l1) + 1

  override def etaReduce: Exp = Pi(arg, domain.etaReduce, codomain.etaReduce)

  override def contains(v: Var): Boolean = domain.contains(v) || (if (v == arg) {
    false
  } else {
    codomain.contains(v)
  })
}

object ToHost {
  def substThrowing(x: Exp, subst: HashMap[Var, Any]): Any = this.applyThrowing(x, List(), subst)

  def subst(x: Exp, subst: HashMap[Var, Any]): Option[Any] = try {
    Some(substThrowing(x, subst))
  } catch {
    case _: ClassCastException | _: IllegalStateException => None
    case e: Throwable => throw e
  }

  def applyThrowing(x: Exp, args: List[Any] = List(), subst: HashMap[Var, Any] = HashMap()): Any = x.betaReduce match {
    case x: Var => aux(subst.get(x), args)
    case The(_, x) => applyThrowing(x, args, subst)
    case Lambda(arg, exp) => aux((hostArg => this.substThrowing(exp, subst.updated(arg, hostArg))): Any => Any, args)
    case _: Apply | _: Universe | _: Pi => throw new IllegalStateException(s"unexpected $x")
  }

  def apply(x: Exp, args: List[Any] = List(), subst: HashMap[Var, Any] = HashMap()): Option[Any] = try {
    Some(applyThrowing(x, args, subst))
  } catch {
    case _: ClassCastException | _: IllegalStateException => None
    case e: Throwable => throw e
  }

  private def aux(x: Any, args: List[Any]): Any = args match {
    case arg0 :: args => aux(x.asInstanceOf[Any => Any](arg0), args)
    case Nil => x
  }
}

object NatUtils {
  private val succ: Nat => Nat = x => x + 1
  private val zero: Nat = 0

  def toHost(x: Exp): Option[Nat] = ToHost.apply(x, List(succ, zero)).map(_.asInstanceOf[Nat])

  private def aux(x: Nat): Exp = if (x == 0) {
    Var("x")
  } else {
    Apply(Var("f"), aux(x - 1))
  }

  def toExp(x: Nat): Exp = Lambda(Var("f"), Lambda(Var("x"), aux(x)))
}