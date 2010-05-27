package purescala

object Common {
  import TypeTrees.Typed

  // the type is left blank (NoType) for Identifiers that are not variables
  class Identifier private[Common](val name: String, val id: Int) extends Typed {
    override def equals(other: Any): Boolean = {
      if(other == null || !other.isInstanceOf[Identifier])
        false
      else
        other.asInstanceOf[Identifier].id == this.id
    }

    override def hashCode: Int = id

    override def toString: String = {
      if(purescala.Settings.showIDs) {
        // angle brackets: name + "\u3008" + id + "\u3009"
        name + "[" + id + "]"
      } else {
        name
      }
    }
  }

  private object UniqueCounter {
    private var soFar: Int = -1

    def next: Int = {
      soFar = soFar + 1
      soFar
    }
  }

  object FreshIdentifier {
    def apply(name: String) : Identifier = new Identifier(name, UniqueCounter.next)
  }
}
