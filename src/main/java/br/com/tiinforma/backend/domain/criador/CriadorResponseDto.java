package br.com.tiinforma.backend.domain.criador;

import br.com.tiinforma.backend.domain.enums.Funcao;
import lombok.Getter;
import lombok.Setter;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class CriadorResponseDto {
    private Long id;
    private String nome;
    private String email;
    private String formacao;
    private Funcao funcao;
    private Integer totalInscritos;
    private String fotoUrl;
}
