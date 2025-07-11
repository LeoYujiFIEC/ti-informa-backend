package br.com.tiinforma.backend.domain.video;

import br.com.tiinforma.backend.domain.criador.CriadorResponseDto;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.util.List;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class VideoResponseDto{
    private Long id;
    private String titulo;
    private String thumbnail;
    private String descricao;
    private String url;
    private String categoria;
    private LocalDate dataPublicacao;
    private List<String> palavraChave;
    private String key;
    private Long visualizacoes;
    private Double avaliacaoMedia;
    private CriadorResponseDto criador;
}
